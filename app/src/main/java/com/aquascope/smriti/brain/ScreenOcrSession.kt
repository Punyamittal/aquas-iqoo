package com.aquascope.smriti.brain

/** Latest pinned screen OCR text for Ask / Qwen grounding. */
object ScreenOcrSession {
    @Volatile var lastScreenOcr: String = ""
    @Volatile var lastScreenOcrPath: String? = null
}
