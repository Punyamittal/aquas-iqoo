package com.aquascope.ui

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.aquascope.R
import com.aquascope.databinding.ActivityEvidenceBinding
import com.aquascope.smriti.SmritiCore
import com.aquascope.smriti.model.EvidenceState

class EvidenceActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityEvidenceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val id = intent.getStringExtra("event_id")
        val smriti = SmritiCore.get(this)
        val event = id?.let { smriti.store.getEvent(it) }
        val bundle = id?.let { smriti.evidenceFor(it) }

        if (event == null || bundle == null) {
            Toast.makeText(this, "No evidence available", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.textClaim.text = bundle.claim
        binding.textState.setText(
            when (bundle.evidenceState) {
                EvidenceState.CONFIRMED -> R.string.ask_state_confirmed
                EvidenceState.OBSERVED -> R.string.ask_state_observed
                EvidenceState.INFERRED -> R.string.ask_state_inferred
                EvidenceState.POSSIBLE -> R.string.ask_state_possible
                EvidenceState.UNKNOWN -> R.string.ask_state_unknown
            }
        )
        binding.textAnomalyScore.text = "${event.anomalyScore.toInt()}%"
        val scoreColor = getColor(if (event.anomalyScore > 30) R.color.amber else R.color.cyan)
        binding.textAnomalyScore.setTextColor(scoreColor)
        binding.textState.setTextColor(scoreColor)

        val pad = (8 * resources.displayMetrics.density).toInt()
        fun line(text: String, colorRes: Int) = TextView(this).apply {
            this.text = text
            setTextColor(getColor(colorRes))
            textSize = 15f
            setLineSpacing(0f, 1.3f)
            setPadding(0, pad, 0, pad)
        }

        binding.layoutSupporting.removeAllViews()
        bundle.supporting.forEach { binding.layoutSupporting.addView(line(it, R.color.warm_white)) }

        binding.layoutUnknown.removeAllViews()
        bundle.unknown.forEach { binding.layoutUnknown.addView(line(it, R.color.warm_muted)) }

        binding.btnCloseEvidence.setOnClickListener { finish() }
    }
}
