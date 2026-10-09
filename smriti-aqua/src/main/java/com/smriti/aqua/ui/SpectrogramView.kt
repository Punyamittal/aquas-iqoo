package com.smriti.aqua.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/**
 * Log-mel spectrogram renderer: row-major FloatArray [frames][mels], values 0..1.
 * Heatmap: navy -> cyan -> pale cyan, matching the home palette. Band edges 17–23 kHz.
 */
@Composable
fun SpectrogramView(
    spectrogram: FloatArray?,
    frames: Int,
    mels: Int,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        border = BorderStroke(1.dp, Hairline),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "17–23 kHz echo spectrogram",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (spectrogram != null && frames > 0) {
                    Text(
                        "$frames frames",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (spectrogram == null || frames <= 0 || mels <= 0 || spectrogram.size < frames * mels) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No scan yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Row {
                    // Frequency axis: 23 kHz at top, 17 kHz at bottom.
                    Column(
                        modifier = Modifier.height(180.dp),
                    ) {
                        Text("23k", style = MaterialTheme.typography.labelSmall, color = WarmFaint)
                        Box(modifier = Modifier.weight(1f))
                        Text("17k", style = MaterialTheme.typography.labelSmall, color = WarmFaint)
                    }
                    Canvas(
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .fillMaxWidth()
                            .height(180.dp),
                    ) {
                        val cellW = size.width / frames
                        val cellH = size.height / mels
                        for (f in 0 until frames) {
                            val rowBase = f * mels
                            for (m in 0 until mels) {
                                val v = spectrogram[rowBase + m].coerceIn(0f, 1f)
                                drawRect(
                                    color = heat(v),
                                    topLeft = Offset(f * cellW, (mels - 1 - m) * cellH),
                                    size = Size(cellW + 0.6f, cellH + 0.6f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** navy -> cyan -> pale cyan. */
private fun heat(t: Float): Color = if (t < 0.5f) {
    lerp(NavyMid, Cyan, t * 2f)
} else {
    lerp(Cyan, CyanBloom, (t - 0.5f) * 2f)
}
