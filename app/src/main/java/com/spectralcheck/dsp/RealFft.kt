package com.spectralcheck.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Table-driven FFT for real input of power-of-two size [n]: packs the signal
 * into a complex FFT of size n/2 and untangles the result, which is about
 * twice as fast as a complex FFT of size n. Holds scratch buffers, so it is
 * NOT thread-safe — create one instance per thread.
 */
class RealFft(val n: Int) {

    private val half = n / 2
    private val re = FloatArray(half)
    private val im = FloatArray(half)
    private val bitrev = IntArray(half)
    private val stageCos: FloatArray
    private val stageSin: FloatArray
    private val untCos = FloatArray(half)
    private val untSin = FloatArray(half)

    init {
        require(n >= 8 && n and (n - 1) == 0) { "size must be a power of two >= 8" }
        val bits = Integer.numberOfTrailingZeros(half)
        for (i in 0 until half) bitrev[i] = Integer.reverse(i) ushr (32 - bits)

        // Twiddles for every butterfly stage, concatenated
        val tc = FloatArray(half - 1)
        val ts = FloatArray(half - 1)
        var off = 0
        var len = 2
        while (len <= half) {
            for (k in 0 until len / 2) {
                val ang = -2.0 * PI * k / len
                tc[off + k] = cos(ang).toFloat()
                ts[off + k] = sin(ang).toFloat()
            }
            off += len / 2
            len = len shl 1
        }
        stageCos = tc
        stageSin = ts

        // e^(-2*pi*i*k/n) for the real/complex untangling step
        for (k in 0 until half) {
            val ang = -2.0 * PI * k / n
            untCos[k] = cos(ang).toFloat()
            untSin[k] = sin(ang).toFloat()
        }
    }

    /**
     * Writes |FFT(x[offset .. offset+n) * window)| into [out] for bins
     * 0..n/2 inclusive ([out] must have n/2+1 elements).
     */
    fun magnitudes(x: FloatArray, offset: Int, window: FloatArray, out: FloatArray) {
        // Pack even samples into re, odd into im, applying window + bit reversal
        for (i in 0 until half) {
            val s = bitrev[i]
            re[i] = x[offset + 2 * s] * window[2 * s]
            im[i] = x[offset + 2 * s + 1] * window[2 * s + 1]
        }

        // Complex FFT of size half with precomputed twiddles
        var len = 2
        var off = 0
        while (len <= half) {
            val h = len / 2
            var i = 0
            while (i < half) {
                for (k in 0 until h) {
                    val c = stageCos[off + k]
                    val s = stageSin[off + k]
                    val a = i + k
                    val b = a + h
                    val tr = re[b] * c - im[b] * s
                    val ti = re[b] * s + im[b] * c
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
                i += len
            }
            off += h
            len = len shl 1
        }

        // Untangle the packed spectrum into real-signal magnitudes
        out[0] = abs(re[0] + im[0])
        out[half] = abs(re[0] - im[0])
        for (k in 1 until half) {
            val hk = half - k
            val xer = (re[k] + re[hk]) * 0.5f
            val xei = (im[k] - im[hk]) * 0.5f
            val xor = (im[k] + im[hk]) * 0.5f
            val xoi = -(re[k] - re[hk]) * 0.5f
            // W = (c, s) with the negative angle already baked into untSin
            val c = untCos[k]
            val s = untSin[k]
            val wr = c * xor - s * xoi
            val wi = c * xoi + s * xor
            val xr = xer + wr
            val xi = xei + wi
            out[k] = sqrt(xr * xr + xi * xi)
        }
    }
}
