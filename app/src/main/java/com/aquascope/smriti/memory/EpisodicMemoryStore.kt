package com.aquascope.smriti.memory

import android.content.Context
import com.aquascope.smriti.model.HomeModel
import com.aquascope.smriti.model.ObjectBaseline
import com.aquascope.smriti.model.PhysicalEvent
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * [ROLE] On-device episodic JSON memory beside locations.json.
 * [WHY] Source of truth for SMRITI Ask / timeline — no cloud upload.
 * [IQOO] Survives offline demos; pairs with Neural Core SQLite episodes.
 */
class EpisodicMemoryStore(context: Context) {

    private val gson = Gson()
    private val dir = File(context.filesDir, "smriti_memory").also { it.mkdirs() }
    private val eventsFile = File(dir, "episodes.json")
    private val homeFile = File(dir, "home_model.json")
    private val baselinesFile = File(dir, "object_baselines.json")

    fun loadEvents(): MutableList<PhysicalEvent> = readList(eventsFile)

    fun saveEvents(events: List<PhysicalEvent>) = writeAtomic(eventsFile, events)

    fun appendEvent(event: PhysicalEvent) {
        val all = loadEvents()
        all.add(event)
        saveEvents(all)
    }

    fun getEvent(id: String): PhysicalEvent? = loadEvents().find { it.id == id }

    fun updateEvent(event: PhysicalEvent) {
        val all = loadEvents()
        val idx = all.indexOfFirst { it.id == event.id }
        if (idx >= 0) {
            all[idx] = event
            saveEvents(all)
        }
    }

    fun eventsForLocation(locationId: String): List<PhysicalEvent> =
        loadEvents().filter { it.locationId == locationId }.sortedByDescending { it.timestampMs }

    fun eventsForObject(objectId: String): List<PhysicalEvent> =
        loadEvents().filter { it.objectId == objectId }.sortedByDescending { it.timestampMs }

    fun loadHome(): HomeModel {
        if (!homeFile.exists()) return HomeModel()
        return try {
            gson.fromJson(homeFile.readText(), HomeModel::class.java) ?: HomeModel()
        } catch (_: Exception) {
            HomeModel()
        }
    }

    fun saveHome(home: HomeModel) = writeAtomic(homeFile, home)

    fun loadBaselines(): MutableList<ObjectBaseline> = readList(baselinesFile)

    fun saveBaselines(baselines: List<ObjectBaseline>) = writeAtomic(baselinesFile, baselines)

    fun upsertBaseline(baseline: ObjectBaseline) {
        val all = loadBaselines()
        val idx = all.indexOfFirst { it.id == baseline.id }
        if (idx >= 0) all[idx] = baseline else all.add(baseline)
        saveBaselines(all)
    }

    fun getBaseline(id: String): ObjectBaseline? = loadBaselines().find { it.id == id }

    private inline fun <reified T> readList(file: File): MutableList<T> {
        if (!file.exists()) return mutableListOf()
        return try {
            val json = file.readText()
            if (json.isBlank()) return mutableListOf()
            val type = object : TypeToken<MutableList<T>>() {}.type
            gson.fromJson<MutableList<T>>(json, type) ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun writeAtomic(file: File, data: Any) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(gson.toJson(data))
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }
}
