package com.smriti.aqua.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

/** Scan tab: calibrate / inspect controls, verdict card, spectrogram, privacy HUD. */
@Composable
fun ScanScreen(vm: ScanViewModel) {
    val state by vm.state.collectAsState()
    var simulator by remember { mutableStateOf(vm.useSimulator) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "DSP lab",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = state.status,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = state.objectId,
            onValueChange = vm::onObjectIdChange,
            label = { Text("Object ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Demo rig simulator", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.weight(1f))
            Switch(
                checked = simulator,
                onCheckedChange = {
                    simulator = it
                    vm.useSimulator = it
                },
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(
                onClick = vm::calibrate,
                enabled = !state.busy,
                border = BorderStroke(1.dp, Hairline),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = WarmWhite),
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
            ) {
                Text("Calibrate", fontWeight = FontWeight.Medium)
            }
            Button(
                onClick = vm::inspect,
                enabled = !state.busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Cyan,
                    contentColor = NavyDeep,
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
            ) {
                Text("Inspect", fontWeight = FontWeight.Medium)
            }
        }

        state.verdict?.let { verdict ->
            VerdictCard(
                verdict = verdict,
                anomalyScore = state.anomalyScore,
                coherence = state.coherence,
            )
        }

        SpectrogramView(
            spectrogram = state.spectrogram,
            frames = state.frames,
            mels = state.mels,
            modifier = Modifier.fillMaxWidth(),
        )

        PrivacyHud(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun VerdictCard(verdict: String, anomalyScore: Float?, coherence: Float?) {
    val good = !verdict.startsWith("Unusual")
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (good) CyanSoft else AmberSoft,
            contentColor = WarmWhite,
        ),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                verdict,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = if (good) Cyan else Amber,
            )
            anomalyScore?.let {
                Text(
                    "Deviation from the baseline fingerprint: ${fmt(it)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            coherence?.let {
                Text(
                    "Mic and accelerometer agreement: ${fmt(it)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun fmt(v: Float): String = String.format(Locale.US, "%.2f", v)
