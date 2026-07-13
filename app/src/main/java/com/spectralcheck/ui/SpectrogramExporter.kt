package com.spectralcheck.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import com.spectralcheck.analysis.AnalysisResult
import com.spectralcheck.analysis.Verdict

/**
 * Renders a SoX-spectrogram-style annotated PNG: dark background, SoX
 * palette, frequency/time axes with tick values, a dB legend bar, and the
 * analysis results printed on the image.
 */
object SpectrogramExporter {

    private const val DB_SPAN = 120f

    private const val SPEC_W = 1400
    private const val SPEC_H = 700
    private const val MARGIN_L = 100f
    private const val MARGIN_T = 110f
    private const val MARGIN_R = 170f
    private const val MARGIN_B = 130f

    fun render(result: AnalysisResult): Bitmap {
        val dbCeil = SpectrogramBitmap.autoCeil(result.spectrogram)
        val spec = SpectrogramBitmap.render(result.spectrogram, Palettes.SOX, dbCeil, DB_SPAN)

        val width = (MARGIN_L + SPEC_W + MARGIN_R).toInt()
        val height = (MARGIN_T + SPEC_H + MARGIN_B).toInt()
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)

        val specRect = RectF(MARGIN_L, MARGIN_T, MARGIN_L + SPEC_W, MARGIN_T + SPEC_H)
        val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(spec, null, specRect, bmpPaint)
        spec.recycle()

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(221, 221, 221)
            textSize = 24f
            typeface = Typeface.MONOSPACE
        }
        val line = Paint().apply {
            color = Color.rgb(150, 150, 150)
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }

        canvas.drawRect(specRect, line)
        drawTitle(canvas, result, text)
        drawFreqAxis(canvas, specRect, result.spectrogram.sampleRate, text, line)
        drawTimeAxis(canvas, specRect, result.spectrogram.let { it.frames.size * it.frameDurationSec }, text, line)
        drawLegend(canvas, specRect, dbCeil, text, line)
        drawCutoffMarker(canvas, specRect, result)
        drawFooter(canvas, result, dbCeil, text)
        return out
    }

    private fun drawTitle(canvas: Canvas, result: AnalysisResult, text: Paint) {
        val title = Paint(text).apply {
            textSize = 34f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            color = Color.WHITE
        }
        canvas.drawText(result.fileName, MARGIN_L, 44f, title)

        val v = result.verdict
        val sub = buildString {
            append(v.verdict.exportLabel())
            v.estimatedSource?.let { append("  est. source: $it") }
            if (!v.cutoff.lowEnergy) {
                append("  cutoff: %.1f kHz".format(v.cutoff.cutoffHz / 1000f))
                append(
                    if (v.cutoff.sharpShelf)
                        " (brick wall, %.0f Hz rolloff)".format(v.cutoff.rolloffWidthHz)
                    else " (gradual rolloff)"
                )
            }
        }
        val subPaint = Paint(text).apply {
            color = when (v.verdict) {
                Verdict.AUTHENTIC -> Color.rgb(102, 187, 106)
                Verdict.SUSPICIOUS -> Color.rgb(255, 179, 0)
                Verdict.TRANSCODE -> Color.rgb(239, 83, 80)
                Verdict.INCONCLUSIVE -> Color.rgb(158, 158, 158)
            }
        }
        canvas.drawText(sub, MARGIN_L, 82f, subPaint)
    }

    private fun Verdict.exportLabel() = when (this) {
        Verdict.AUTHENTIC -> "LIKELY AUTHENTIC"
        Verdict.SUSPICIOUS -> "SUSPICIOUS"
        Verdict.TRANSCODE -> "TRANSCODE"
        Verdict.INCONCLUSIVE -> "INCONCLUSIVE"
    }

    private fun drawFreqAxis(canvas: Canvas, r: RectF, sampleRate: Int, text: Paint, line: Paint) {
        val nyquistKhz = sampleRate / 2000f
        val stepKhz = floatArrayOf(1f, 2f, 5f, 10f).firstOrNull { nyquistKhz / it <= 12f } ?: 20f
        val right = Paint(text).apply { textAlign = Paint.Align.RIGHT }
        var k = 0f
        while (k <= nyquistKhz + 0.01f) {
            val y = r.bottom - (k / nyquistKhz) * r.height()
            canvas.drawLine(r.left - 8f, y, r.left, y, line)
            canvas.drawText("%.0fk".format(k), r.left - 14f, y + 8f, right)
            k += stepKhz
        }
        // Axis caption, rotated
        canvas.save()
        canvas.rotate(-90f, 24f, r.centerY())
        val cap = Paint(text).apply { textAlign = Paint.Align.CENTER }
        canvas.drawText("Frequency (Hz)", 24f, r.centerY(), cap)
        canvas.restore()
    }

    private fun drawTimeAxis(canvas: Canvas, r: RectF, totalSec: Float, text: Paint, line: Paint) {
        val step = floatArrayOf(1f, 2f, 5f, 10f, 15f, 30f, 60f, 120f, 300f)
            .firstOrNull { totalSec / it <= 10f } ?: 600f
        val center = Paint(text).apply { textAlign = Paint.Align.CENTER }
        var t = 0f
        while (t <= totalSec + 0.01f) {
            val x = r.left + (t / totalSec) * r.width()
            canvas.drawLine(x, r.bottom, x, r.bottom + 8f, line)
            val s = t.toInt()
            canvas.drawText("%d:%02d".format(s / 60, s % 60), x, r.bottom + 36f, center)
            t += step
        }
        val cap = Paint(text).apply { textAlign = Paint.Align.CENTER }
        canvas.drawText("Time (m:ss)", r.centerX(), r.bottom + 70f, cap)
    }

    private fun drawLegend(canvas: Canvas, r: RectF, dbCeil: Float, text: Paint, line: Paint) {
        val barLeft = r.right + 36f
        val barW = 28f
        // Gradient bar, top = 0 dB
        val p = Paint()
        val h = r.height().toInt()
        for (i in 0 until h) {
            val t = 1f - i.toFloat() / h
            p.color = Palettes.color(Palettes.SOX, t)
            canvas.drawRect(barLeft, r.top + i, barLeft + barW, r.top + i + 1f, p)
        }
        canvas.drawRect(barLeft, r.top, barLeft + barW, r.bottom, line)

        var db = dbCeil.toInt()
        val floor = (dbCeil - DB_SPAN).toInt()
        while (db >= floor) {
            val y = r.top + ((dbCeil - db) / DB_SPAN) * r.height()
            canvas.drawLine(barLeft + barW, y, barLeft + barW + 6f, y, line)
            canvas.drawText("$db", barLeft + barW + 12f, y + 8f, text)
            db -= 20
        }
        canvas.drawText("dBFS", barLeft, r.top - 14f, text)
    }

    private fun drawCutoffMarker(canvas: Canvas, r: RectF, result: AnalysisResult) {
        val cutoff = result.verdict.cutoff
        if (cutoff.lowEnergy || cutoff.cutoffHz <= 0f) return
        val nyquist = result.spectrogram.sampleRate / 2f
        if (cutoff.cutoffHz >= nyquist * 0.99f) return

        val y = r.bottom - (cutoff.cutoffHz / nyquist) * r.height()
        val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.CYAN
            strokeWidth = 2.5f
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
        }
        canvas.drawLine(r.left, y, r.right, y, dash)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.CYAN
            textSize = 24f
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.RIGHT
        }
        val txt = "cutoff %.1f kHz".format(cutoff.cutoffHz / 1000f)
        val bounds = Rect()
        label.getTextBounds(txt, 0, txt.length, bounds)
        val ty = if (y - 12f - bounds.height() < r.top) y + 12f + bounds.height() else y - 12f
        canvas.drawText(txt, r.right - 12f, ty, label)
    }

    private fun drawFooter(canvas: Canvas, result: AnalysisResult, dbCeil: Float, text: Paint) {
        val i = result.info
        val s = result.spectrogram
        val footer = "%d Hz · %d-bit · %d ch · %d:%02d · FFT %d Hann, hop %d · %.0f..%.0f dBFS · SpectralCheck"
            .format(
                i.sampleRate, i.bitDepth, i.channels,
                i.durationMs / 60000, i.durationMs / 1000 % 60,
                s.fftSize, s.hopSize, dbCeil - DB_SPAN, dbCeil,
            )
        val p = Paint(text).apply { color = Color.rgb(150, 150, 150) }
        canvas.drawText(footer, MARGIN_L, MARGIN_T + SPEC_H + 106f, p)
    }
}
