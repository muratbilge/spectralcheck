package com.spectralcheck.ui

import androidx.compose.ui.graphics.Color
import com.spectralcheck.analysis.Signal
import com.spectralcheck.analysis.Verdict

val Verdict.color: Color
    get() = when (this) {
        Verdict.AUTHENTIC -> Color(0xFF4CAF50)
        Verdict.SUSPICIOUS -> Color(0xFFFFB300)
        Verdict.TRANSCODE -> Color(0xFFE53935)
        Verdict.INCONCLUSIVE -> Color(0xFF9E9E9E)
    }

val Signal.color: Color
    get() = when (this) {
        Signal.LOSSY -> Color(0xFFE53935)
        Signal.LOSSLESS -> Color(0xFF4CAF50)
        Signal.NEUTRAL -> Color(0xFF9E9E9E)
    }

val Verdict.label: String
    get() = when (this) {
        Verdict.AUTHENTIC -> "Likely authentic"
        Verdict.SUSPICIOUS -> "Suspicious"
        Verdict.TRANSCODE -> "Transcode"
        Verdict.INCONCLUSIVE -> "Inconclusive"
    }
