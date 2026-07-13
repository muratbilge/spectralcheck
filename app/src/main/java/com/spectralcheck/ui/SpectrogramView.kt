package com.spectralcheck.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val MAX_SCALE = 16f

/**
 * Spectrogram image with pinch-zoom/pan (double-tap toggles zoom) and
 * frequency/time scales that follow the visible window while zooming.
 * The bitmap's top edge is Nyquist, bottom is 0 Hz.
 */
@Composable
fun SpectrogramView(
    bitmap: Bitmap,
    sampleRate: Int,
    durationMs: Long,
    cutoffHz: Float?,
    modifier: Modifier = Modifier,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    fun clampX(v: Float) = v.coerceIn(0f, (scale - 1f) * boxSize.width)
    fun clampY(v: Float) = v.coerceIn(0f, (scale - 1f) * boxSize.height)

    // Visible content window in [0, 1] fractions of the full image
    val h = boxSize.height.toFloat().coerceAtLeast(1f)
    val w = boxSize.width.toFloat().coerceAtLeast(1f)
    val topFrac = offsetY / (scale * h)
    val bottomFrac = (offsetY + h) / (scale * h)
    val leftFrac = offsetX / (scale * w)
    val rightFrac = (offsetX + w) / (scale * w)

    val nyquist = sampleRate / 2f
    val freqTop = nyquist * (1f - topFrac)
    val freqBottom = nyquist * (1f - bottomFrac)
    val totalSec = durationMs / 1000f
    val timeLeft = totalSec * leftFrac
    val timeRight = totalSec * rightFrac

    Column(modifier = modifier) {
        Row(Modifier.fillMaxWidth()) {
            // Frequency axis labels — follow the visible window
            Column(
                Modifier.padding(end = 4.dp).height(280.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                val spanKhz = (freqTop - freqBottom) / 1000f
                for (i in 0..4) {
                    val khz = (freqTop + (freqBottom - freqTop) * i / 4f) / 1000f
                    Text(
                        if (spanKhz < 8f) "%.1fk".format(khz) else "%.0fk".format(khz),
                        fontSize = 10.sp,
                        color = Color.Gray,
                    )
                }
            }

            Column(Modifier.weight(1f)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .clipToBounds()
                        .background(Color.Black)
                        .onSizeChanged { boxSize = it }
                        .pointerInput(Unit) {
                            detectTransformGestures { centroid, pan, zoom, _ ->
                                val old = scale
                                scale = (scale * zoom).coerceIn(1f, MAX_SCALE)
                                // Keep the point under the fingers fixed while
                                // zooming; pan moves the content with the fingers.
                                offsetX = clampX((offsetX + centroid.x) * (scale / old) - centroid.x - pan.x)
                                offsetY = clampY((offsetY + centroid.y) * (scale / old) - centroid.y - pan.y)
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = { tap ->
                                if (scale > 1f) {
                                    scale = 1f
                                    offsetX = 0f
                                    offsetY = 0f
                                } else {
                                    scale = 3f
                                    offsetX = clampX(tap.x * 3f - boxSize.width / 2f)
                                    offsetY = clampY(tap.y * 3f - boxSize.height / 2f)
                                }
                            })
                        },
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Spectrogram",
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier
                            .fillMaxSize()
                            // Lambda form: transform state is read in the draw
                            // phase only, so gestures never trigger recomposition.
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = -offsetX
                                translationY = -offsetY
                                transformOrigin = TransformOrigin(0f, 0f)
                            },
                    )

                    // Cutoff frequency marker line
                    if (cutoffHz != null && cutoffHz > 0) {
                        Canvas(Modifier.fillMaxSize()) {
                            val yFrac = 1f - cutoffHz / nyquist
                            val y = yFrac * size.height * scale - offsetY
                            if (y in 0f..size.height) {
                                drawLine(
                                    color = Color.Cyan.copy(alpha = 0.7f),
                                    start = Offset(0f, y),
                                    end = Offset(size.width, y),
                                    strokeWidth = 2f,
                                )
                            }
                        }
                    }
                }

                // Time axis — follows the visible window, aligned under the image
                Row(
                    Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    val span = timeRight - timeLeft
                    for (i in 0..4) {
                        val t = timeLeft + span * i / 4f
                        val min = (t / 60f).toInt()
                        val sec = t - min * 60
                        Text(
                            if (span < 30f) "%d:%04.1f".format(min, sec.coerceAtMost(59.9f))
                            else "%d:%02d".format(min, sec.toInt()),
                            fontSize = 10.sp,
                            color = Color.Gray,
                        )
                    }
                }
            }
        }
    }
}
