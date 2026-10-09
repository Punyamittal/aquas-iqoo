package com.aquascope.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.aquascope.R
import com.aquascope.data.ScanLocation
import com.aquascope.data.ScanRepository
import com.aquascope.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class MainActivity : SmritiScreenActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repo: ScanRepository
    private lateinit var adapter: LocationAdapter

    companion object {
        private const val RC_AUDIO_PERMISSION = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = ScanRepository(this)
        adapter = LocationAdapter(
            onScan = { location -> launchScan(location) },
            onHistory = { location ->
                startActivity(Intent(this, HistoryActivity::class.java).apply {
                    putExtra("location_id", location.id)
                })
            },
            onDelete = { location -> confirmDelete(location) }
        )

        binding.recyclerLocations.layoutManager = LinearLayoutManager(this)
        binding.recyclerLocations.adapter = adapter
        binding.fabNewScan.setOnClickListener { showNewLocationDialog() }
        SmritiNav.bind(this, binding.bottomNav, SmritiNav.TAB_SCAN)

        ensureAudioPermission()
        refreshPlaces()
    }

    override fun onResume() {
        super.onResume()
        refreshPlaces()
    }

    private fun refreshPlaces() {
        val locations = repo.loadLocations()
        adapter.submitList(locations)
        val empty = locations.isEmpty()
        binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        binding.acousticFieldBg.visibility = if (empty) View.VISIBLE else View.GONE
        binding.recyclerLocations.visibility = if (empty) View.GONE else View.VISIBLE
        bindSummary(locations)
    }

    private fun bindSummary(locations: List<ScanLocation>) {
        if (locations.isEmpty()) {
            binding.cardScanSummary.visibility = View.GONE
            return
        }
        binding.cardScanSummary.visibility = View.VISIBLE
        val calibrated = locations.count { it.baselineFeatures.isNotEmpty() }
        binding.textScanSummary.text = if (locations.size == 1) {
            getString(R.string.scan_summary_one, calibrated)
        } else {
            getString(R.string.scan_summary_places, locations.size, calibrated)
        }
        val last = locations
            .mapNotNull { loc -> loc.scanHistory.maxByOrNull { it.timestamp }?.let { loc to it } }
            .maxByOrNull { it.second.timestamp }
        binding.textScanLast.text = if (last == null) {
            getString(R.string.scan_no_observations)
        } else {
            getString(
                R.string.scan_last_observation,
                last.first.label,
                relativeTime(last.second.timestamp)
            )
        }
    }

    private fun showNewLocationDialog() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val inputLayout = TextInputLayout(this).apply {
            hint = "Location name"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxStrokeColorStateList(
                ContextCompat.getColorStateList(this@MainActivity, R.color.cyan)!!
            )
            setHintTextColor(ContextCompat.getColorStateList(this@MainActivity, R.color.warm_muted))
            setPadding(pad, pad / 2, pad, 0)
        }
        val input = TextInputEditText(inputLayout.context).apply {
            setHint("Kitchen wall — left of sink")
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.warm_white))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.warm_faint))
        }
        inputLayout.addView(input)

        MaterialAlertDialogBuilder(this)
            .setTitle("Add a place")
            .setMessage("Name the wall, pipe, or corner you will hold the phone against.")
            .setView(inputLayout)
            .setPositiveButton("Add and scan") { _, _ ->
                val label = input.text?.toString()?.trim().orEmpty()
                if (label.isEmpty()) {
                    Toast.makeText(this, "Enter a location name", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val location = ScanLocation(label = label)
                repo.addLocation(location)
                launchScan(location)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(location: ScanLocation) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Remove this place?")
            .setMessage("Remove \"${location.label}\" and all of its baselines and scan history?")
            .setPositiveButton("Remove") { _, _ ->
                repo.deleteLocation(location.id)
                onResume()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun launchScan(location: ScanLocation) {
        if (!hasAudioPermission()) {
            ensureAudioPermission()
            return
        }
        startActivity(Intent(this, ScanActivity::class.java).apply {
            putExtra("location_id", location.id)
        })
    }

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureAudioPermission() {
        if (!hasAudioPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                RC_AUDIO_PERMISSION
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RC_AUDIO_PERMISSION &&
            grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(
                this,
                "Microphone permission is required for scanning",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
