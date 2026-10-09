package com.aquascope.smriti.llm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * [ROLE] UI→sandbox proxy for on-device LLM generate/warmup.
 * [WHY] MediaPipe lives in LlmSandboxService; crashes stay out of Ask.
 * [IQOO] Used by SmritiCore.ask on every grounded question.
 */
class IsolatedLlmClient(context: Context) : LocalLlmEngine {

    private val app = context.applicationContext
    private val gate = Any()
    private val callLock = Any()
    private val replies = HandlerThread("smriti-llm-ipc").apply { start() }
    private val replyMessenger = Messenger(ReplyHandler(replies))

    private var connection: ServiceConnection? = null
    private var remote: Messenger? = null
    private var death: IBinder.DeathRecipient? = null

    private val pendingLatch = AtomicReference<CountDownLatch?>(null)
    private val pendingBundle = AtomicReference<Bundle?>(null)

    @Volatile private var boundReady = false
    @Volatile private var nativeUnstable = false
    @Volatile private var label: String? = null
    @Volatile private var lastError: String? = null
    @Volatile private var recoveries = 0

    override val isReady: Boolean
        get() = boundReady && !nativeUnstable && remote != null

    override val modelLabel: String?
        get() = if (isReady) label else null

    val crashedNative: Boolean
        get() = nativeUnstable

    fun ensureReady(timeoutMs: Long = WARMUP_TIMEOUT_MS): Boolean = synchronized(callLock) {
        ensureReadyLocked(timeoutMs)
    }

    private fun ensureReadyLocked(timeoutMs: Long): Boolean {
        if (isReady) return true
        if (nativeUnstable) {
            if (recoveries >= MAX_RECOVERIES) {
                lastError = lastError ?: "Local model process crashed. Reload from System."
                return false
            }
            Log.i(TAG, "retrying LLM sandbox after prior death (${recoveries + 1}/$MAX_RECOVERIES)")
            recoveries++
            nativeUnstable = false
            unbind()
        }
        if (!bind(BIND_TIMEOUT_MS)) {
            lastError = lastError ?: "Could not start local model process"
            return false
        }
        val reply = transact(MSG_WARMUP, Bundle(), timeoutMs)
        if (reply == null) {
            lastError = lastError ?: "Local model warmup timed out"
            return false
        }
        val ok = reply.getBoolean(KEY_OK, false)
        if (ok) {
            boundReady = true
            label = reply.getString(KEY_MODEL)
            lastError = null
            recoveries = 0
            Log.i(TAG, "sandbox ready: $label")
        } else {
            boundReady = false
            lastError = reply.getString(KEY_MESSAGE) ?: "MediaPipe did not load"
            Log.w(TAG, "sandbox warmup failed: $lastError")
        }
        return ok && !nativeUnstable
    }

    fun reload() = synchronized(callLock) {
        nativeUnstable = false
        boundReady = false
        label = null
        lastError = null
        recoveries = 0
        unbind()
        ensureReadyLocked(WARMUP_TIMEOUT_MS)
    }

    fun lastFailure(): String? = lastError

    override fun generate(prompt: String): String? = synchronized(callLock) {
        if (!ensureReadyLocked(WARMUP_TIMEOUT_MS)) return@synchronized null
        val data = Bundle().apply { putString(KEY_PROMPT, prompt) }
        val reply = transact(MSG_GENERATE, data, GENERATE_TIMEOUT_MS)
        if (reply == null) {
            if (nativeUnstable && recoveries < MAX_RECOVERIES) {
                Log.w(TAG, "generate interrupted; retrying sandbox once")
                if (ensureReadyLocked(WARMUP_TIMEOUT_MS)) {
                    val retry = transact(MSG_GENERATE, data, GENERATE_TIMEOUT_MS)
                    return@synchronized readGenerate(retry)
                }
            }
            lastError = lastError ?: "Local model timed out while answering"
            return@synchronized null
        }
        readGenerate(reply)
    }

    private fun readGenerate(reply: Bundle?): String? {
        if (reply == null) return null
        if (!reply.getBoolean(KEY_OK, false)) {
            lastError = reply.getString(KEY_MESSAGE) ?: "empty generation"
            return null
        }
        val text = reply.getString(KEY_TEXT)?.trim()?.takeIf { it.isNotEmpty() }
        if (text == null) {
            lastError = "Model produced no text"
        } else {
            lastError = null
        }
        return text
    }

    override fun close() = synchronized(callLock) {
        try {
            transact(MSG_CLOSE, Bundle(), 2_000)
        } catch (_: Throwable) {
        }
        boundReady = false
        unbind()
    }

    private fun bind(timeoutMs: Long): Boolean {
        if (nativeUnstable) return false
        synchronized(gate) {
            if (remote != null) return true
        }
        val connected = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val messenger = Messenger(service)
                val recipient = IBinder.DeathRecipient { onSandboxDied() }
                try {
                    service?.linkToDeath(recipient, 0)
                } catch (_: RemoteException) {
                    onSandboxDied()
                    connected.countDown()
                    return
                }
                synchronized(gate) {
                    remote = messenger
                    death = recipient
                }
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                onSandboxDied()
                connected.countDown()
            }
        }
        val ok = try {
            app.bindService(
                Intent(app, LlmSandboxService::class.java),
                conn,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
            )
        } catch (t: Throwable) {
            Log.w(TAG, "bindService failed", t)
            false
        }
        if (!ok) return false
        synchronized(gate) { connection = conn }
        if (!connected.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "bind timeout")
            unbind()
            return false
        }
        return remote != null && !nativeUnstable
    }

    private fun transact(what: Int, data: Bundle, timeoutMs: Long): Bundle? {
        val target = synchronized(gate) { remote } ?: return null
        val latch = CountDownLatch(1)
        pendingBundle.set(null)
        pendingLatch.set(latch)
        val msg = Message.obtain(null, what)
        msg.data = data
        msg.replyTo = replyMessenger
        return try {
            target.send(msg)
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "IPC timeout what=$what")
                null
            } else if (nativeUnstable) {
                null
            } else {
                pendingBundle.get()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "IPC send failed what=$what", t)
            if (t is RemoteException) onSandboxDied()
            null
        } finally {
            pendingLatch.compareAndSet(latch, null)
        }
    }

    private fun onSandboxDied() {
        Log.w(TAG, "LLM sandbox process died — Ask will keep using rules")
        nativeUnstable = true
        boundReady = false
        pendingLatch.get()?.countDown()
        unbind()
    }

    private fun unbind() {
        val snapshot = synchronized(gate) {
            val s = Triple(death, remote?.binder, connection)
            death = null
            remote = null
            connection = null
            s
        }
        val (recipient, binder, conn) = snapshot
        if (recipient != null && binder != null) {
            try {
                binder.unlinkToDeath(recipient, 0)
            } catch (_: Throwable) {
            }
        }
        if (conn != null) {
            try {
                app.unbindService(conn)
            } catch (_: Throwable) {
            }
        }
    }

    private inner class ReplyHandler(thread: HandlerThread) : Handler(thread.looper) {
        override fun handleMessage(msg: Message) {
            val copy = msg.data?.let { Bundle(it) } ?: Bundle()
            pendingBundle.set(copy)
            pendingLatch.get()?.countDown()
        }
    }

    companion object {
        private const val TAG = "IsolatedLlm"
        const val MSG_WARMUP = 1
        const val MSG_GENERATE = 2
        const val MSG_CLOSE = 3
        const val KEY_OK = "ok"
        const val KEY_PROMPT = "prompt"
        const val KEY_TEXT = "text"
        const val KEY_MODEL = "model"
        const val KEY_MESSAGE = "message"
        const val KEY_PID = "pid"
        private const val BIND_TIMEOUT_MS = 8_000L
        private const val WARMUP_TIMEOUT_MS = 120_000L // Qwen 1.5B can take a while
        private const val GENERATE_TIMEOUT_MS = 90_000L
        private const val MAX_RECOVERIES = 2
    }
}
