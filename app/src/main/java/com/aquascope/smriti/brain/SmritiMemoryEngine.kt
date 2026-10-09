package com.aquascope.smriti.brain

import android.content.Context
import com.aquascope.smriti.brain.db.EpisodeEntity
import com.aquascope.smriti.brain.db.SmritiBrainDatabase
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

data class EpisodeRecord(
    val id: String,
    val timestampMs: Long,
    val kind: TaxonomyParser.Kind,
    val title: String,
    val body: String,
    val fields: Map<String, String>,
    val evidencePath: String?,
    val source: String,
    val score: Float = 1f
)

data class RecallResult(
    val found: Boolean,
    val message: String,
    val matches: List<EpisodeRecord>
)

/**
 * SQLite + FTS5 + hot in-RAM cosine vector store.
 * Grounding contract: no match → "No record found".
 */
class SmritiMemoryEngine(context: Context) {

    private val app = context.applicationContext
    private val db = SmritiBrainDatabase.get(app)
    private val dao = db.episodes()
    private val gson = Gson()
    private val mutex = Mutex()
    private val hot = CopyOnWriteArrayList<Pair<EpisodeRecord, FloatArray>>()
    private val clipsDir = File(app.filesDir, "smriti_clips").also { it.mkdirs() }

    suspend fun ingest(
        raw: String,
        source: String,
        evidencePath: String? = null,
        kindOverride: TaxonomyParser.Kind? = null
    ): EpisodeRecord = withContext(Dispatchers.IO) {
        val parsed = TaxonomyParser.parse(raw)
        val kind = kindOverride ?: parsed.kind
        val embedding = HashingEmbedder.embed("${parsed.title} ${parsed.fields.values.joinToString(" ")} $raw")
        val record = EpisodeRecord(
            id = UUID.randomUUID().toString(),
            timestampMs = System.currentTimeMillis(),
            kind = kind,
            title = parsed.title,
            body = raw.trim(),
            fields = parsed.fields,
            evidencePath = evidencePath,
            source = source
        )
        persist(record, embedding)
        record
    }

    suspend fun recall(query: String, limit: Int = 8): RecallResult = withContext(Dispatchers.IO) {
        mutex.withLock { if (hot.isEmpty()) reloadHot() }
        val q = query.trim()
        if (q.isEmpty()) {
            return@withContext RecallResult(false, NO_RECORD, emptyList())
        }
        val qVec = HashingEmbedder.embed(q)
        val ftsHits = fts(q)
        val scored = LinkedHashMap<String, EpisodeRecord>()
        ftsHits.forEach { scored[it.id] = it.copy(score = 0.55f) }
        hot.forEach { (rec, vec) ->
            val cos = HashingEmbedder.cosine(qVec, vec)
            if (cos >= COSINE_FLOOR) {
                val prev = scored[rec.id]
                val best = maxOf(prev?.score ?: 0f, cos)
                scored[rec.id] = rec.copy(score = best)
            }
        }
        // Game / screen questions: always surface recent ScreenMind clips even if FTS misses.
        val ql = q.lowercase(java.util.Locale.getDefault())
        val hasFreshClip = com.aquascope.smriti.brain.ScreenOcrSession.lastScreenOcr.isNotBlank()
        val wantsScreen = hasFreshClip || ql.contains("game") || ql.contains("screen") ||
            ql.contains("clip") || ql.contains("recording") || ql.contains("on screen") ||
            ql.contains("peace") || ql.contains("highlight") || ql.contains("kill") ||
            (ql.contains("app") && (ql.contains("open") || ql.contains("opened")))
        if (wantsScreen) {
            hot.asSequence()
                .map { it.first }
                .filter {
                    it.source.equals("SCREENMIND", true) ||
                        it.source.equals("SMRITI_PLAY", true) ||
                        it.source.equals("PEACE", true) ||
                        it.source.equals("OCR", true) ||
                        it.kind == TaxonomyParser.Kind.GAME ||
                        it.body.contains("ScreenMind", true) ||
                        it.body.contains("PEACE", true) ||
                        it.body.contains("Game/App opened", true)
                }
                .sortedByDescending { it.timestampMs }
                .take(if (hasFreshClip) 2 else 4)
                .forEachIndexed { index, rec ->
                    val boost = if (index == 0) 0.98f else 0.72f
                    val prev = scored[rec.id]
                    scored[rec.id] = rec.copy(score = maxOf(prev?.score ?: 0f, boost))
                }
        }
        val ranked = scored.values
            .sortedWith(compareByDescending<EpisodeRecord> { it.score }.thenByDescending { it.timestampMs })
            .take(limit)
            .filter {
                it.score >= COSINE_FLOOR ||
                    it.body.contains(q, ignoreCase = true) ||
                    (wantsScreen && (
                        it.source.equals("SCREENMIND", true) ||
                            it.source.equals("PEACE", true) ||
                            it.kind == TaxonomyParser.Kind.GAME ||
                            it.body.contains("ScreenMind", true) ||
                            it.body.contains("PEACE", true) ||
                            it.body.contains("Game/App opened", true)
                        ))
            }
        if (ranked.isEmpty()) {
            RecallResult(false, NO_RECORD, emptyList())
        } else {
            val top = ranked.first()
            val whenText = java.text.SimpleDateFormat("d MMM yyyy HH:mm", java.util.Locale.US)
                .format(java.util.Date(top.timestampMs))
            RecallResult(
                found = true,
                message = buildString {
                    append("Recorded $whenText — ${top.title}")
                    val body = top.body.trim()
                    if (body.isNotBlank()) {
                        val detail = body
                            .removePrefix(top.title)
                            .trim()
                            .ifBlank { body }
                        append("\n\n")
                        append(detail.take(600))
                    }
                    if (!top.evidencePath.isNullOrBlank()) {
                        append("\n\n(Clip saved — open the Play row to watch)")
                    }
                },
                matches = ranked
            )
        }
    }

    suspend fun timeline(limit: Int = 40): List<EpisodeRecord> = withContext(Dispatchers.IO) {
        dao.recent(limit).map { it.toRecord() }
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) { dao.count() }

    fun clipsDir(): File = clipsDir

    fun trimClipStorage(maxBytes: Long = MAX_CLIP_BYTES, keepNewest: File? = null) {
        val keepPath = keepNewest?.absolutePath
        val all = clipsDir.listFiles()?.toList().orEmpty()
        var total = all.sumOf { it.length() }
        val victims = all
            .filter { keepPath == null || it.absolutePath != keepPath }
            .sortedBy { it.lastModified() }
        for (f in victims) {
            if (total <= maxBytes) break
            total -= f.length()
            f.delete()
        }
    }

    private suspend fun persist(record: EpisodeRecord, embedding: FloatArray) {
        mutex.withLock {
            dao.upsert(record.toEntity(embedding))
            val sqlite = db.openHelper.writableDatabase
            val safeTitle = record.title.replace("'", " ")
            val safeBody = record.body.replace("'", " ")
            sqlite.execSQL(
                "INSERT INTO episodes_fts(id, title, body) VALUES (?,?,?)",
                arrayOf(record.id, safeTitle, safeBody)
            )
            hot.add(0, record to embedding)
            if (hot.size > HOT_CAP) hot.removeAt(hot.lastIndex)
        }
    }

    private suspend fun fts(query: String): List<EpisodeRecord> {
        val tokens = HashingEmbedder.tokenize(query)
        if (tokens.isEmpty()) return emptyList()
        val match = tokens.joinToString(" OR ") { it.replace(Regex("[^a-z0-9]"), "") }
            .ifBlank { return emptyList() }
        return try {
            val c = db.openHelper.readableDatabase.query(
                "SELECT id FROM episodes_fts WHERE episodes_fts MATCH ? LIMIT 24",
                arrayOf(match)
            )
            val ids = mutableListOf<String>()
            c.use {
                while (it.moveToNext()) ids.add(it.getString(0))
            }
            ids.mapNotNull { dao.byId(it)?.toRecord() }
        } catch (_: Throwable) {
            dao.like(query.take(48), 12).map { it.toRecord() }
        }
    }

    private suspend fun reloadHot() {
        hot.clear()
        dao.all().takeLast(HOT_CAP).reversed().forEach { row ->
            hot.add(row.toRecord() to HashingEmbedder.fromBlob(row.embedding))
        }
    }

    private fun EpisodeRecord.toEntity(embedding: FloatArray) = EpisodeEntity(
        id = id,
        timestampMs = timestampMs,
        kind = kind.name,
        title = title,
        body = body,
        fieldsJson = gson.toJson(fields),
        evidencePath = evidencePath,
        embedding = HashingEmbedder.toBlob(embedding),
        source = source
    )

    private fun EpisodeEntity.toRecord(): EpisodeRecord {
        val kind = try {
            TaxonomyParser.Kind.valueOf(kind)
        } catch (_: Exception) {
            TaxonomyParser.Kind.UNKNOWN
        }
        @Suppress("UNCHECKED_CAST")
        val fields = try {
            gson.fromJson(fieldsJson, Map::class.java) as? Map<String, String> ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
        return EpisodeRecord(id, timestampMs, kind, title, body, fields, evidencePath, source)
    }

    companion object {
        const val NO_RECORD = "No record found"
        private const val COSINE_FLOOR = 0.18f
        private const val HOT_CAP = 400
        private const val MAX_CLIP_BYTES = 8L * 1024L * 1024L * 1024L

        @Volatile private var instance: SmritiMemoryEngine? = null

        fun get(context: Context): SmritiMemoryEngine {
            return instance ?: synchronized(this) {
                instance ?: SmritiMemoryEngine(context.applicationContext).also { instance = it }
            }
        }
    }
}
