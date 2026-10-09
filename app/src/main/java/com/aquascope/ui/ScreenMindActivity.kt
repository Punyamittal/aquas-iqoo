package com.aquascope.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aquascope.R
import com.aquascope.databinding.ActivityScreenmindBinding
import com.aquascope.halo.SmritiLightState
import com.aquascope.smriti.screenmind.ScreenMindBridge
import com.aquascope.smriti.screenmind.ScreenMindLocalEngine
import kotlinx.coroutines.launch

class ScreenMindActivity : SmritiScreenActivity() {

    private lateinit var binding: ActivityScreenmindBinding
    private lateinit var localEngine: ScreenMindLocalEngine
    private lateinit var bridge: ScreenMindBridge
    private lateinit var haloBind: HaloBinding
    private val allItemsList = mutableListOf<DisplayItem>()
    private val displayedList = mutableListOf<DisplayItem>()
    private lateinit var adapter: ScreenMindAdapter
    private var activeFilter: String = "ALL"

    data class DisplayItem(
        val id: String,
        val app: String,
        val category: String,
        val time: String,
        val summary: String,
        val details: String = "",
        val isPhysicalScan: Boolean = false,
        var isExpanded: Boolean = false
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScreenmindBinding.inflate(layoutInflater)
        setContentView(binding.root)

        localEngine = ScreenMindLocalEngine.get(this)
        bridge = ScreenMindBridge.get(this)
        haloBind = HaloBinding(this, binding.haloIndicator)

        binding.inputServerUrl.setText(bridge.getServerUrl())

        adapter = ScreenMindAdapter(displayedList)
        binding.recyclerResults.layoutManager = LinearLayoutManager(this)
        binding.recyclerResults.adapter = adapter

        // Search & On-Device Q&A
        binding.btnSearch.setOnClickListener {
            val q = binding.inputQuery.text?.toString()?.trim().orEmpty()
            if (q.isNotEmpty()) {
                performSearch(q)
            } else {
                loadTimeline()
            }
        }

        binding.btnAskDesktop.setOnClickListener {
            val q = binding.inputQuery.text?.toString()?.trim().orEmpty()
            if (q.isNotEmpty()) {
                performChat(q)
            } else {
                Toast.makeText(this, "Type a question in the search box first", Toast.LENGTH_SHORT).show()
            }
        }

        // On-Device Screen Capture
        binding.btnCaptureScreen.setOnClickListener {
            triggerScreenCapture()
        }

        // Cross-Sync Physical Sensor Scans
        binding.btnSyncScans.setOnClickListener {
            syncPhysicalScans()
        }

        // Seed Realistic Demo Memories
        binding.btnSeedDemo.setOnClickListener {
            seedDemoData()
        }

        // Filter chips
        binding.chipFilterAll.setOnClickListener { applyFilter("ALL") }
        binding.chipFilterDesktop.setOnClickListener { applyFilter("SCREEN") }
        binding.chipFilterAquaScope.setOnClickListener { applyFilter("ACOUSTIC") }

        // Developer PC Sync (Optional)
        binding.btnToggleDevSync.setOnClickListener {
            val isVis = binding.layoutDevSync.visibility == View.VISIBLE
            binding.layoutDevSync.visibility = if (isVis) View.GONE else View.VISIBLE
            binding.btnToggleDevSync.setText(
                if (isVis) R.string.screenmind_pc_show else R.string.screenmind_pc_hide
            )
        }

        binding.btnConnect.setOnClickListener {
            val url = binding.inputServerUrl.text?.toString()?.trim().orEmpty()
            if (url.isNotEmpty()) {
                bridge.setServerUrl(url)
                checkRemoteConnection()
            }
        }

        // Load timeline on start (100% on-device!)
        loadTimeline()
    }

    override fun onResume() {
        super.onResume()
        haloBind.start()
    }

    override fun onPause() {
        haloBind.stop()
        super.onPause()
    }

    private fun loadTimeline() {
        binding.progressLoading.visibility = View.VISIBLE
        binding.textEmpty.visibility = View.GONE
        haloBind.controller().setState(SmritiLightState.MEMORY_RECALL)

        lifecycleScope.launch {
            val items = localEngine.getTimeline(50)
            binding.progressLoading.visibility = View.GONE
            haloBind.controller().setState(SmritiLightState.NORMAL)

            allItemsList.clear()
            items.forEach {
                allItemsList.add(
                    DisplayItem(
                        id = it.id,
                        app = it.appName,
                        category = it.category,
                        time = it.timestamp,
                        summary = it.summary,
                        details = it.details,
                        isPhysicalScan = it.isPhysicalScan
                    )
                )
            }
            applyFilter(activeFilter)
        }
    }

    private fun performSearch(query: String) {
        binding.progressLoading.visibility = View.VISIBLE
        binding.textEmpty.visibility = View.GONE
        haloBind.controller().setState(SmritiLightState.MEMORY_RECALL)

        lifecycleScope.launch {
            val items = localEngine.search(query)
            binding.progressLoading.visibility = View.GONE
            haloBind.controller().setState(SmritiLightState.NORMAL)

            allItemsList.clear()
            items.forEach {
                allItemsList.add(
                    DisplayItem(
                        id = it.id,
                        app = it.appName,
                        category = it.category,
                        time = it.timestamp,
                        summary = it.summary,
                        details = if (it.details.isNotBlank()) it.details else "${(it.score * 100).toInt()}% match",
                        isPhysicalScan = it.isPhysicalScan
                    )
                )
            }
            applyFilter(activeFilter)
        }
    }

    private fun performChat(query: String) {
        binding.progressLoading.visibility = View.VISIBLE
        binding.textEmpty.visibility = View.GONE
        haloBind.controller().setState(SmritiLightState.PROCESSING)

        lifecycleScope.launch {
            val answer = localEngine.ask(query)
            binding.progressLoading.visibility = View.GONE
            haloBind.controller().setState(SmritiLightState.NORMAL)

            allItemsList.add(
                0,
                DisplayItem(
                    id = "chat-answer-${System.currentTimeMillis()}",
                    app = "Answer",
                    category = "On this phone",
                    time = "Just now",
                    summary = answer,
                    details = "Written on this phone from your saved screens and wall scans. Nothing was sent online.",
                    isExpanded = true
                )
            )
            applyFilter("ALL")
        }
    }

    private fun triggerScreenCapture() {
        captureCurrentViewSnapshot("AquaScope Screen")
    }

    private fun captureCurrentViewSnapshot(appHint: String) {
        binding.progressLoading.visibility = View.VISIBLE
        haloBind.controller().setState(SmritiLightState.EXTRACTION)

        lifecycleScope.launch {
            try {
                val root = window.decorView.rootView
                val bitmap = Bitmap.createBitmap(
                    root.width.coerceAtLeast(720),
                    root.height.coerceAtLeast(1280),
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(bitmap)
                root.draw(canvas)

                val episode = localEngine.processBitmapAndIngest(bitmap, appHint)
                binding.progressLoading.visibility = View.GONE
                haloBind.controller().setState(SmritiLightState.NORMAL)

                Toast.makeText(this@ScreenMindActivity, "Screen saved and its text is searchable", Toast.LENGTH_SHORT).show()
                loadTimeline()
            } catch (t: Throwable) {
                binding.progressLoading.visibility = View.GONE
                haloBind.controller().setState(SmritiLightState.NORMAL)
                Toast.makeText(this@ScreenMindActivity, "Could not capture the screen: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun syncPhysicalScans() {
        binding.progressLoading.visibility = View.VISIBLE
        haloBind.controller().setState(SmritiLightState.PROCESSING)

        lifecycleScope.launch {
            val count = localEngine.syncPhysicalAcousticScans()
            binding.progressLoading.visibility = View.GONE
            haloBind.controller().setState(SmritiLightState.NORMAL)

            val msg = if (count == 1) "Imported 1 wall scan" else "Imported $count wall scans"
            Toast.makeText(this@ScreenMindActivity, msg, Toast.LENGTH_SHORT).show()
            loadTimeline()
        }
    }

    private fun seedDemoData() {
        binding.progressLoading.visibility = View.VISIBLE
        haloBind.controller().setState(SmritiLightState.PROCESSING)

        lifecycleScope.launch {
            val count = localEngine.seedDemoData()
            binding.progressLoading.visibility = View.GONE
            haloBind.controller().setState(SmritiLightState.NORMAL)

            Toast.makeText(this@ScreenMindActivity, "Loaded $count demo memories", Toast.LENGTH_SHORT).show()
            loadTimeline()
        }
    }

    private fun checkRemoteConnection() {
        binding.textStatusIndicator.setText(R.string.screenmind_pc_checking)
        binding.textStatusIndicator.setTextColor(getColor(R.color.amber))
        lifecycleScope.launch {
            val status = bridge.probeConnection()
            if (status.connected) {
                binding.textStatusIndicator.setText(R.string.screenmind_pc_connected)
                binding.textStatusIndicator.setTextColor(getColor(R.color.cyan))
                Toast.makeText(this@ScreenMindActivity, "Connected to the PC", Toast.LENGTH_SHORT).show()
            } else {
                binding.textStatusIndicator.setText(R.string.screenmind_pc_not_connected)
                binding.textStatusIndicator.setTextColor(getColor(R.color.status_alert))
                Toast.makeText(this@ScreenMindActivity, "Could not reach the PC. Search still works on this phone.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun applyFilter(filter: String) {
        activeFilter = filter
        styleFilterChip(binding.chipFilterAll, filter == "ALL")
        styleFilterChip(binding.chipFilterDesktop, filter == "SCREEN")
        styleFilterChip(binding.chipFilterAquaScope, filter == "ACOUSTIC")

        displayedList.clear()
        when (filter) {
            "SCREEN" -> displayedList.addAll(allItemsList.filter { !it.isPhysicalScan && !it.app.contains("Sensor", true) && !it.app.contains("Acoustic", true) })
            "ACOUSTIC" -> displayedList.addAll(allItemsList.filter { it.isPhysicalScan || it.app.contains("Sensor", true) || it.app.contains("Acoustic", true) })
            else -> displayedList.addAll(allItemsList)
        }
        adapter.notifyDataSetChanged()

        val empty = displayedList.isEmpty()
        binding.textEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        if (empty) {
            binding.textEmpty.setText(
                when (filter) {
                    "ACOUSTIC" -> R.string.screenmind_empty_scans
                    "SCREEN" -> R.string.screenmind_empty_screens
                    else -> R.string.screenmind_empty_notice
                }
            )
        }
        binding.recyclerResults.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun styleFilterChip(chip: TextView, selected: Boolean) {
        chip.isSelected = selected
        chip.setBackgroundResource(if (selected) R.drawable.bg_chip_dark_selected else R.drawable.bg_ask_pill)
        chip.setTextColor(getColor(if (selected) R.color.navy_deep else R.color.warm_muted))
    }

    inner class ScreenMindAdapter(
        private val list: List<DisplayItem>
    ) : RecyclerView.Adapter<ScreenMindAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textApp: TextView = view.findViewById(R.id.textItemApp)
            val textCategory: TextView = view.findViewById(R.id.textItemCategory)
            val textTime: TextView = view.findViewById(R.id.textItemTime)
            val textSummary: TextView = view.findViewById(R.id.textItemSummary)
            val textDetails: TextView = view.findViewById(R.id.textItemDetails)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_screenmind_result, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            holder.textApp.text = item.app
            holder.textCategory.text = item.category.replace('_', ' ')
                .lowercase()
                .replaceFirstChar { it.uppercase() }
            holder.textTime.text = item.time
            holder.textSummary.text = item.summary

            if (item.details.isNotBlank()) {
                holder.textDetails.text = item.details
                holder.textDetails.visibility = if (item.isExpanded) View.VISIBLE else View.GONE
            } else {
                holder.textDetails.visibility = View.GONE
            }

            holder.itemView.setOnClickListener {
                if (item.details.isNotBlank()) {
                    item.isExpanded = !item.isExpanded
                    notifyItemChanged(position)
                }
            }
        }

        override fun getItemCount(): Int = list.size
    }
}
