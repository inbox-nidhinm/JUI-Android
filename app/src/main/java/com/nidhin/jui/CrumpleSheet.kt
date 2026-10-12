package com.nidhin.jui

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// A sticky, put away, is a sheet crumpled into a ball in the line, right after
// its words. Tapped, the ball lifts, comes to the middle of the screen and
// opens out flat into the card; put away again, the card crushes back into a
// ball and drops back into its place in the line. The prototype this came
// from is the iPhone repo's `prototypes/crumpled-sticky/index.html` (the
// "fluid" motion, crease depth 0.8) — every number here is carried over from
// it, by way of the iPhone app's Crumple.swift.
//
// This file is the paper itself: its creases and its two shapes, flat and
// balled. Drawing it is CrumpleGL.kt; running it is CrumpleDirector.kt.

// MARK: - Creases

/** Perlin noise, seeded as the prototype's, so the ball has the same creases. */
object Crease {
    private val perm: IntArray = run {
        val p = IntArray(256) { it }
        var seed = 7L
        for (i in 255 downTo 1) {
            seed = (seed * 16807) % 2147483647
            val j = (seed % (i + 1)).toInt()
            val t = p[i]; p[i] = p[j]; p[j] = t
        }
        IntArray(512) { p[it and 255] }
    }

    private fun fade(t: Float) = t * t * t * (t * (t * 6 - 15) + 10)
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun grad(h: Int, x: Float, y: Float, z: Float): Float {
        val u = if (h < 8) x else y
        val v = if (h < 4) y else if (h == 12 || h == 14) x else z
        return (if ((h and 1) != 0) -u else u) + (if ((h and 2) != 0) -v else v)
    }

    fun noise(xIn: Float, yIn: Float, zIn: Float): Float {
        val fx = floor(xIn); val fy = floor(yIn); val fz = floor(zIn)
        val X = fx.toInt() and 255; val Y = fy.toInt() and 255; val Z = fz.toInt() and 255
        val x = xIn - fx; val y = yIn - fy; val z = zIn - fz
        val u = fade(x); val v = fade(y); val w = fade(z)
        val A = perm[X] + Y; val AA = perm[A] + Z; val AB = perm[A + 1] + Z
        val B = perm[X + 1] + Y; val BA = perm[B] + Z; val BB = perm[B + 1] + Z
        return lerp(
            lerp(lerp(grad(perm[AA] and 15, x, y, z), grad(perm[BA] and 15, x - 1, y, z), u),
                lerp(grad(perm[AB] and 15, x, y - 1, z), grad(perm[BB] and 15, x - 1, y - 1, z), u), v),
            lerp(lerp(grad(perm[AA + 1] and 15, x, y, z - 1), grad(perm[BA + 1] and 15, x - 1, y, z - 1), u),
                lerp(grad(perm[AB + 1] and 15, x, y - 1, z - 1), grad(perm[BB + 1] and 15, x - 1, y - 1, z - 1), u), v),
            w)
    }

    /** Sharp lines where the noise crosses zero: fold lines. */
    fun ridge(x: Float, y: Float, z: Float): Float {
        val n = 1 - abs(noise(x, y, z))
        return n * n
    }
}

object Ease {
    fun clamp(t: Float) = t.coerceIn(0f, 1f)
    fun outCubic(t: Float) = 1 - (1 - t).pow(3)
    fun inOutCubic(t: Float) = if (t < 0.5f) 4 * t * t * t else 1 - (-2 * t + 2).pow(3) / 2
}

// MARK: - The sheet

/**
 * One sticky's sheet: every point of it laid flat (the card) and crumpled (the
 * ball), and when each starts to open. Points are in the card's own points,
 * centred, y up. `widthPts × heightPts` is the card.
 */
class CrumpleSheet(val widthPts: Float, val heightPts: Float, depth: Float = DEPTH) {
    companion object {
        /** How crinkled the ball is, 0…1 — the prototype's slider, settled at 0.8. */
        const val DEPTH = 0.8f
        /** The ball in the line, across — a little over the type's height. */
        const val BALL_DIAMETER = 24f
        /**
         * The centre of the sheet opens first and the edges last, by this much
         * of the unfold.
         */
        const val DELAY_SPREAD = 0.32f
    }

    val columns = 56
    val rows: Int
    /**
     * The ball's radius before it is scaled down to the line — sized to the
     * sheet's area, so a tall sticky makes a ball as round as a square one.
     */
    val radius: Float
    private val flat: FloatArray
    private val ball: FloatArray
    private val delay: FloatArray
    private val wrinkle: FloatArray
    val count: Int

    init {
        val w = widthPts.coerceAtLeast(1f)
        val h = heightPts.coerceAtLeast(1f)
        rows = min(140, maxOf(8, (56 * h / w).roundToInt()))
        radius = 58 * sqrt(w * h) / 280
        count = (rows + 1) * (columns + 1)
        flat = FloatArray(count * 3)
        ball = FloatArray(count * 3)
        delay = FloatArray(count)
        wrinkle = FloatArray(count)
        var i = 0
        for (row in 0..rows) {
            for (column in 0..columns) {
                val x = (column.toFloat() / columns - 0.5f) * w
                val y = (0.5f - row.toFloat() / rows) * h
                flat[i * 3] = x; flat[i * 3 + 1] = y; flat[i * 3 + 2] = 0f
                val u = x / (w / 2); val v = y / (h / 2)
                val r = min(1f, sqrt(u * u + v * v) / sqrt(2f))
                // Gathered like a handkerchief: the middle of the sheet faces
                // out, the edges are drawn round to the back, with a twist as
                // they go.
                val theta = atan2(v, u) + r * 1.4f
                val alpha = r * Math.PI.toFloat() * 0.93f
                val dx = sin(alpha) * cos(theta); val dy = sin(alpha) * sin(theta); val dz = cos(alpha)
                val lump = 1 + 0.14f * Crease.noise(dx * 1.6f + 3.1f, dy * 1.6f, dz * 1.6f)
                val coarse = Crease.ridge(dx * 2.6f, dy * 2.6f, dz * 2.6f)
                val fine = Crease.ridge(dx * 5.3f + 9, dy * 5.3f, dz * 5.3f)
                val crease = coarse * 0.62f + fine * 0.38f - 0.5f
                val reach = radius * (lump + crease * 0.42f * depth)
                ball[i * 3] = dx * reach; ball[i * 3 + 1] = dy * reach; ball[i * 3 + 2] = dz * reach
                delay[i] = r * DELAY_SPREAD
                // Creases left in the paper as it flattens, gone once it's flat.
                val broad = Crease.ridge(u * 2.2f + 4, v * 2.2f, 1.7f)
                val small = Crease.ridge(u * 4.4f, v * 4.4f + 2, 5.1f)
                wrinkle[i] = (broad * 0.7f + small * 0.3f - 0.45f) * 26 * depth
                i++
            }
        }
    }

    /** Texture coordinates, the image's top left at (0, 0). */
    fun uvs(): FloatArray {
        val out = FloatArray(count * 2)
        var i = 0
        for (row in 0..rows) for (column in 0..columns) {
            out[i++] = column.toFloat() / columns
            out[i++] = row.toFloat() / rows
        }
        return out
    }

    /** Two triangles a cell, wound so the card's face is the front (CCW from +z). */
    fun indices(): ShortArray {
        val out = ShortArray(columns * rows * 6)
        val stride = columns + 1
        var k = 0
        for (row in 0 until rows) for (column in 0 until columns) {
            val a = column + stride * row; val b = column + stride * (row + 1)
            val c = column + 1 + stride * (row + 1); val d = column + 1 + stride * row
            out[k++] = a.toShort(); out[k++] = b.toShort(); out[k++] = d.toShort()
            out[k++] = b.toShort(); out[k++] = c.toShort(); out[k++] = d.toShort()
        }
        return out
    }

    /** Every point, `unfold` of the way from the ball to flat (0…1), into `out` (xyz). */
    fun positions(unfold: Float, out: FloatArray) {
        val span = 1 - DELAY_SPREAD
        for (i in 0 until count) {
            val p = Ease.inOutCubic(Ease.clamp((unfold - delay[i]) / span))
            val settle = 4 * p * (1 - p) // creases linger mid-way
            val j = i * 3
            out[j] = ball[j] + (flat[j] - ball[j]) * p
            out[j + 1] = ball[j + 1] + (flat[j + 1] - ball[j + 1]) * p
            out[j + 2] = ball[j + 2] + (flat[j + 2] - ball[j + 2]) * p + wrinkle[i] * settle
        }
    }
}
