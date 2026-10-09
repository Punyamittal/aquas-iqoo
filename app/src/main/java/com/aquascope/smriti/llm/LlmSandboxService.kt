package com.aquascope.smriti.llm

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log

/**
 * [ROLE] Private-process MediaPipe host (`:smriti_llm`).
 * [WHY] Native LLM crashes must never kill Ask UI or sensing.
 * [IQOO] Keeps on-device Qwen/Gemma stable on Snapdragon flagships.
 */
class LlmSandboxService : Service() {

    private val worker = HandlerThread("smriti-llm-sandbox").apply { start() }
    private var llm: MediaPipeLocalLlm? = null
    private val messenger = Messenger(Incoming(worker))

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        try {
            llm?.close()
        } catch (_: Throwable) {
        }
        llm = null
        worker.quitSafely()
        super.onDestroy()
    }

    private inner class Incoming(thread: HandlerThread) : Handler(thread.looper) {
        override fun handleMessage(msg: Message) {
            val replyTo = msg.replyTo
            if (replyTo == null) {
                Log.w(TAG, "drop msg ${msg.what}: no replyTo")
                return
            }
            val out = Message.obtain()
            out.data = try {
                when (msg.what) {
                    IsolatedLlmClient.MSG_WARMUP -> warmup()
                    IsolatedLlmClient.MSG_GENERATE ->
                        generate(msg.data.getString(IsolatedLlmClient.KEY_PROMPT).orEmpty())
                    IsolatedLlmClient.MSG_CLOSE -> closeEngine()
                    else -> fail("unknown op")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "sandbox handler failed", t)
                fail(t.message ?: t.javaClass.simpleName)
            }
            try {
                replyTo.send(out)
            } catch (t: Throwable) {
                Log.w(TAG, "sandbox reply failed", t)
            }
        }
    }

    private fun warmup(): Bundle {
        if (llm?.isReady == true) {
            return ok(llm?.modelLabel)
        }
        try {
            llm?.close()
        } catch (_: Throwable) {
        }
        val store = LocalModelStore(this)
        llm = MediaPipeLocalLlm.tryCreate(this, store)
        val engine = llm
        return if (engine?.isReady == true) {
            Log.i(TAG, "sandbox model ready pid=${Process.myPid()} ${engine.modelLabel}")
            ok(engine.modelLabel)
        } else {
            val litert = store.findInstalledLiteRt()
            val detail = engine?.lastError()
                ?: if (litert != null) {
                    "Gemma 4 file is on disk (${litert.name}), but Ask still needs a MediaPipe .task " +
                        "(Gemma 3 1B / Qwen) until LiteRT-LM is enabled on this build."
                } else {
                    store.status().message
                }
            Log.w(TAG, "sandbox warmup failed: $detail")
            fail(detail)
        }
    }

    private fun generate(prompt: String): Bundle {
        val engine = llm ?: return fail("sandbox not warmed")
        if (!engine.isReady) return fail("sandbox engine not ready")
        val text = try {
            engine.generate(prompt)
        } catch (t: Throwable) {
            Log.w(TAG, "generate failed", t)
            null
        }
        return if (text.isNullOrBlank()) {
            fail(engine.lastError() ?: "empty generation")
        } else {
            ok(engine.modelLabel, text)
        }
    }

    private fun closeEngine(): Bundle {
        try {
            llm?.close()
        } catch (_: Throwable) {
        }
        llm = null
        return ok(null)
    }

    private fun ok(model: String?, text: String? = null): Bundle = Bundle().apply {
        putBoolean(IsolatedLlmClient.KEY_OK, true)
        putString(IsolatedLlmClient.KEY_MODEL, model)
        putString(IsolatedLlmClient.KEY_TEXT, text)
        putInt(IsolatedLlmClient.KEY_PID, Process.myPid())
    }

    private fun fail(message: String): Bundle = Bundle().apply {
        putBoolean(IsolatedLlmClient.KEY_OK, false)
        putString(IsolatedLlmClient.KEY_MESSAGE, message)
        putInt(IsolatedLlmClient.KEY_PID, Process.myPid())
    }

    companion object {
        private const val TAG = "LlmSandbox"
    }
}
