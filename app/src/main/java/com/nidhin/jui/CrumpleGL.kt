package com.nidhin.jui

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.opengl.GLES30.*
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.sin

// MARK: - The scene

/**
 * The sheet, its shadow and an orthographic camera measured in screen points —
 * the iPhone app's SceneKit scene, held as plain state so it can be posed on
 * the main thread and drawn by `CrumpleRenderer` on whichever thread owns a GL
 * context: the stage over the app, or an offscreen pbuffer for the ball in the
 * line.
 *
 * Everything the renderer reads is guarded by `lock`.
 */
class CrumpleScene {
    val lock = Any()

    // Geometry. A new sheet only when the card's size changes.
    var sheet: CrumpleSheet? = null; private set
    var sheetVersion = 0; private set
    var positions = FloatArray(0); private set

    // Pictures, with versions so the renderer uploads each once.
    var front: Bitmap? = null; private set
    var frontVersion = 0; private set
    var back: Bitmap? = null; private set
    var backVersion = 0; private set
    var shadow: Bitmap? = null; private set
    var shadowVersion = 0; private set
    /** The shadow quad's size in points. */
    var shadowW = 1f; private set
    var shadowH = 1f; private set

    // The pose.
    val model = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    var shadowX = 0f; private set
    var shadowY = 0f; private set
    var shadowOpacity = 0f; private set
    /** False at rest: nothing to show, the stage clears to nothing. */
    var visible = false; private set

    /** The viewport, in points. */
    var viewportW = 1f; private set
    var viewportH = 1f; private set

    fun setViewport(w: Float, h: Float) {
        if (w <= 0 || h <= 0) return
        synchronized(lock) { viewportW = w; viewportH = h }
    }

    /** A new sheet the size of the card (points), with the card's two faces. */
    fun load(widthPts: Float, heightPts: Float, frontImage: Bitmap, backImage: Bitmap) {
        synchronized(lock) {
            val s = sheet
            if (s == null || s.widthPts != widthPts || s.heightPts != heightPts) {
                val made = CrumpleSheet(widthPts, heightPts)
                sheet = made
                positions = FloatArray(made.count * 3)
                sheetVersion++
            }
            front = frontImage; frontVersion++
            back = backImage; backVersion++
            val shade = CrumpleArt.shadow(widthPts, heightPts)
            shadow = shade; shadowVersion++
            // The shadow is drawn a pixel to a point.
            shadowW = shade.width.toFloat()
            shadowH = shade.height.toFloat()
        }
    }

    /** The face, swapped once its words are drawn. */
    fun setFront(image: Bitmap) {
        synchronized(lock) { front = image; frontVersion++ }
    }

    /** Nothing to show: the sheet put away. */
    fun rest() {
        synchronized(lock) { visible = false }
    }

    /**
     * The sheet at `t` of the way from the ball at `mark` (0) to flat over
     * `card` (1), both in screen points. No mark: it crumples to nothing where
     * it is — a sticky deleted, rather than put away.
     */
    fun pose(t: Float, mark: PointF?, card: RectF, elapsed: Float) {
        synchronized(lock) {
            val sheet = sheet ?: return
            val travel = Ease.outCubic(Ease.clamp(t / 0.42f))
            val unfold = Ease.clamp((t - 0.26f) / 0.74f)
            sheet.positions(unfold, positions)

            val endX = card.centerX() - viewportW / 2
            val endY = viewportH / 2 - card.centerY()
            val startX = mark?.let { it.x - viewportW / 2 } ?: endX
            val startY = mark?.let { viewportH / 2 - it.y } ?: endY
            val lift = sin(travel * Math.PI.toFloat()) * 46 // an arc on the way
            val x = startX + (endX - startX) * travel
            val y = startY + (endY - startY) * travel + lift
            val closed = if (mark == null) 0.001f else CrumpleSheet.BALL_DIAMETER / (2 * sheet.radius)
            val scale = closed + (1 - closed) * travel
            val tumble = 1 - unfold
            val pitch = tumble * 0.55f + (1 - travel) * 0.2f * sin(elapsed * 1.2f)
            val yaw = tumble * (0.9f + (1 - travel) * 1.6f)
            val roll = tumble * -0.5f
            // SceneKit's euler angles: roll first, then yaw, then pitch — so
            // the model is T · Rx · Ry · Rz · S.
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, x, y, 0f)
            Matrix.rotateM(model, 0, Math.toDegrees(pitch.toDouble()).toFloat(), 1f, 0f, 0f)
            Matrix.rotateM(model, 0, Math.toDegrees(yaw.toDouble()).toFloat(), 0f, 1f, 0f)
            Matrix.rotateM(model, 0, Math.toDegrees(roll.toDouble()).toFloat(), 0f, 0f, 1f)
            Matrix.scaleM(model, 0, scale, scale, scale)
            shadowX = endX
            shadowY = endY
            // Only as the sheet comes down flat, once it covers the card's
            // footprint — earlier, the clear middle showed as a pale outline.
            shadowOpacity = Ease.clamp((unfold - 0.8f) / 0.2f)
            visible = true
        }
    }
}

// MARK: - Drawing it

/**
 * Draws a `CrumpleScene` into whatever GL ES 3 context is current. Paper is lit
 * as matte facets — the normals come from the facets themselves, so every
 * crease catches the light — and the stamp's perforations are cut out of it.
 *
 * Lit so that the sheet, lying flat, comes out the colour of the card it hands
 * over to: ambient plus the sun's share facing it is 1.
 */
class CrumpleRenderer {
    private var sheetProgram = 0
    private var shadowProgram = 0
    private val buffers = IntArray(4) // positions, uvs, indices, shadow quad
    private val textures = IntArray(3) // front, back, shadow
    private var vao = IntArray(2)
    private var sheetVersion = -1
    private var frontVersion = -1
    private var backVersion = -1
    private var shadowVersion = -1
    private var indexCount = 0
    private var positionBuffer: FloatBuffer? = null
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val mvp = FloatArray(16)
    private val shadowModel = FloatArray(16)

    // The sun at (-400, 600, 1000), looking at the origin: the direction from
    // a surface toward it.
    private val light = run {
        val l = floatArrayOf(-400f, 600f, 1000f)
        val n = Matrix.length(l[0], l[1], l[2])
        floatArrayOf(l[0] / n, l[1] / n, l[2] / n)
    }

    /** Compiles the paper's shaders — at the stage's creation, not on the first tap. */
    fun init() {
        sheetProgram = program(SHEET_VS, SHEET_FS)
        shadowProgram = program(SHADOW_VS, SHADOW_FS)
        glGenBuffers(4, buffers, 0)
        glGenTextures(3, textures, 0)
        glGenVertexArrays(2, vao, 0)
        // The shadow quad: a unit square, centred, with its picture's UVs.
        val quad = floatArrayOf(
            -0.5f, 0.5f, 0f, 0f, -0.5f, -0.5f, 0f, 1f, 0.5f, 0.5f, 1f, 0f, 0.5f, -0.5f, 1f, 1f)
        glBindVertexArray(vao[1])
        glBindBuffer(GL_ARRAY_BUFFER, buffers[3])
        glBufferData(GL_ARRAY_BUFFER, quad.size * 4, floats(quad), GL_STATIC_DRAW)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 16, 0)
        glEnableVertexAttribArray(1)
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 16, 8)
        glBindVertexArray(0)
    }

    /** Draws `scene` into a `widthPx × heightPx` target, cleared to nothing first. */
    fun draw(scene: CrumpleScene, widthPx: Int, heightPx: Int) {
        glViewport(0, 0, widthPx, heightPx)
        glClearColor(0f, 0f, 0f, 0f)
        glClearDepthf(1f)
        glDepthMask(true)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        synchronized(scene.lock) {
            if (!scene.visible) return
            val sheet = scene.sheet ?: return
            upload(scene, sheet)

            val w = scene.viewportW; val h = scene.viewportH
            Matrix.orthoM(proj, 0, -w / 2, w / 2, -h / 2, h / 2, 1f, 4000f)
            Matrix.setLookAtM(view, 0, 0f, 0f, 1000f, 0f, 0f, 0f, 0f, 1f, 0f)
            Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

            // The shadow first, far behind: constant light, no depth written.
            if (scene.shadowOpacity > 0f && scene.shadow != null) {
                Matrix.setIdentityM(shadowModel, 0)
                Matrix.translateM(shadowModel, 0, scene.shadowX, scene.shadowY, -900f)
                Matrix.scaleM(shadowModel, 0, scene.shadowW, scene.shadowH, 1f)
                Matrix.multiplyMM(mvp, 0, vp, 0, shadowModel, 0)
                glDisable(GL_DEPTH_TEST)
                glDepthMask(false)
                glEnable(GL_BLEND)
                glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA) // premultiplied
                glUseProgram(shadowProgram)
                glUniformMatrix4fv(glGetUniformLocation(shadowProgram, "uMVP"), 1, false, mvp, 0)
                glUniform1f(glGetUniformLocation(shadowProgram, "uOpacity"), scene.shadowOpacity)
                glActiveTexture(GL_TEXTURE0)
                glBindTexture(GL_TEXTURE_2D, textures[2])
                glUniform1i(glGetUniformLocation(shadowProgram, "uTex"), 0)
                glBindVertexArray(vao[1])
                glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
                glDepthMask(true)
            }

            // The sheet: both faces, the card's on the front.
            glDisable(GL_BLEND)
            glEnable(GL_DEPTH_TEST)
            glDepthFunc(GL_LESS)
            glDisable(GL_CULL_FACE)
            glUseProgram(sheetProgram)
            glUniformMatrix4fv(glGetUniformLocation(sheetProgram, "uVP"), 1, false, vp, 0)
            glUniformMatrix4fv(glGetUniformLocation(sheetProgram, "uModel"), 1, false, scene.model, 0)
            glUniform3f(glGetUniformLocation(sheetProgram, "uLight"), light[0], light[1], light[2])
            glActiveTexture(GL_TEXTURE0)
            glBindTexture(GL_TEXTURE_2D, textures[0])
            glUniform1i(glGetUniformLocation(sheetProgram, "uFront"), 0)
            glActiveTexture(GL_TEXTURE1)
            glBindTexture(GL_TEXTURE_2D, textures[1])
            glUniform1i(glGetUniformLocation(sheetProgram, "uBack"), 1)
            glBindVertexArray(vao[0])
            val pb = positionBuffer!!
            pb.clear(); pb.put(scene.positions, 0, sheet.count * 3); pb.flip()
            glBindBuffer(GL_ARRAY_BUFFER, buffers[0])
            glBufferSubData(GL_ARRAY_BUFFER, 0, sheet.count * 12, pb)
            glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_SHORT, 0)
            glBindVertexArray(0)
        }
    }

    private fun upload(scene: CrumpleScene, sheet: CrumpleSheet) {
        if (scene.sheetVersion != sheetVersion) {
            sheetVersion = scene.sheetVersion
            glBindVertexArray(vao[0])
            positionBuffer = ByteBuffer.allocateDirect(sheet.count * 12)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            glBindBuffer(GL_ARRAY_BUFFER, buffers[0])
            glBufferData(GL_ARRAY_BUFFER, sheet.count * 12, null, GL_DYNAMIC_DRAW)
            glEnableVertexAttribArray(0)
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0)
            val uvs = sheet.uvs()
            glBindBuffer(GL_ARRAY_BUFFER, buffers[1])
            glBufferData(GL_ARRAY_BUFFER, uvs.size * 4, floats(uvs), GL_STATIC_DRAW)
            glEnableVertexAttribArray(1)
            glVertexAttribPointer(1, 2, GL_FLOAT, false, 8, 0)
            val idx = sheet.indices()
            indexCount = idx.size
            val ib = ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
            ib.put(idx).flip()
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, buffers[2])
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, idx.size * 2, ib, GL_STATIC_DRAW)
            glBindVertexArray(0)
        }
        if (scene.frontVersion != frontVersion) {
            frontVersion = scene.frontVersion
            scene.front?.let { texture(textures[0], it, mipmaps = true) }
        }
        if (scene.backVersion != backVersion) {
            backVersion = scene.backVersion
            scene.back?.let { texture(textures[1], it, mipmaps = true) }
        }
        if (scene.shadowVersion != shadowVersion) {
            shadowVersion = scene.shadowVersion
            scene.shadow?.let { texture(textures[2], it, mipmaps = false) }
        }
    }

    private fun texture(id: Int, bitmap: Bitmap, mipmaps: Boolean) {
        glBindTexture(GL_TEXTURE_2D, id)
        GLUtils.texImage2D(GL_TEXTURE_2D, 0, bitmap, 0)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        if (mipmaps) {
            glGenerateMipmap(GL_TEXTURE_2D)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
        } else {
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        }
    }

    companion object {
        private const val SHEET_VS = """#version 300 es
uniform mat4 uVP;
uniform mat4 uModel;
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUV;
out vec2 vUV;
out vec3 vWorld;
void main() {
    vec4 world = uModel * vec4(aPos, 1.0);
    vWorld = world.xyz;
    vUV = aUV;
    gl_Position = uVP * world;
}"""

        // The card's face on the front, the blank on the back; the paper is
        // opaque, so a pixel the perforations take is dropped rather than
        // blended. Bitmaps arrive premultiplied — divided back out here.
        private const val SHEET_FS = """#version 300 es
precision highp float;
uniform sampler2D uFront;
uniform sampler2D uBack;
uniform vec3 uLight;
in vec2 vUV;
in vec3 vWorld;
out vec4 fragColor;
void main() {
    vec4 c = gl_FrontFacing ? texture(uFront, vUV) : texture(uBack, vUV);
    if (c.a < 0.5) discard;
    vec3 n = normalize(cross(dFdx(vWorld), dFdy(vWorld)));
    if (n.z < 0.0) n = -n; // the face the camera sees
    float lit = 0.6 + 0.5 * max(dot(n, uLight), 0.0);
    fragColor = vec4(min(c.rgb / c.a * lit, vec3(1.0)), 1.0);
}"""

        private const val SHADOW_VS = """#version 300 es
uniform mat4 uMVP;
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUV;
out vec2 vUV;
void main() {
    vUV = aUV;
    gl_Position = uMVP * vec4(aPos, 0.0, 1.0);
}"""

        private const val SHADOW_FS = """#version 300 es
precision mediump float;
uniform sampler2D uTex;
uniform float uOpacity;
in vec2 vUV;
out vec4 fragColor;
void main() {
    fragColor = texture(uTex, vUV) * uOpacity;
}"""

        private fun shader(type: Int, source: String): Int {
            val s = glCreateShader(type)
            glShaderSource(s, source)
            glCompileShader(s)
            val ok = IntArray(1)
            glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "JUI crumple shader: " + glGetShaderInfoLog(s) }
            return s
        }

        private fun program(vs: String, fs: String): Int {
            val p = glCreateProgram()
            glAttachShader(p, shader(GL_VERTEX_SHADER, vs))
            glAttachShader(p, shader(GL_FRAGMENT_SHADER, fs))
            glLinkProgram(p)
            val ok = IntArray(1)
            glGetProgramiv(p, GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { "JUI crumple program: " + glGetProgramInfoLog(p) }
            return p
        }

        private fun floats(a: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                .apply { put(a).flip() }
    }
}
