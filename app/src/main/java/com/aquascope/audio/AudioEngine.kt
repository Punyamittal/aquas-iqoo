package com.aquascope.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * [ROLE] Simultaneous chirp playback + mic recording for AquaScope scans.
 * [WHY] Time-aligned reference + response enable FFT deconvolution.
 * [IQOO] Locked to iQOO 15 bottom speaker and bottom mic; AEC/NS/AGC disabled for raw fidelity.
 */
class AudioEngine(
    private val context: Context,
    private val chirpDurationSec: Double = IqooDeviceProfile.CHIRP_DURATION_SEC
) {
    private val tailDurationSec = IqooDeviceProfile.RECORD_TAIL_SEC

    data class CaptureResult(
        val recorded: DoubleArray,
        val reference: DoubleArray,
        val sampleRate: Int
    )

    suspend fun playAndRecord(
        onAmplitude: ((Float) -> Unit)? = null
    ): CaptureResult = withContext(Dispatchers.IO) {
        val sampleRate = pickSampleRate()
        val totalRecordSamples = ((chirpDurationSec + tailDurationSec) * sampleRate).toInt()
        val chirp = ChirpGenerator.generate(
            startFreq = IqooDeviceProfile.CHIRP_START_HZ,
            endFreq = IqooDeviceProfile.CHIRP_END_HZ,
            sampleRate = sampleRate,
            durationSec = chirpDurationSec
        )
        val chirpMono = ChirpGenerator.toShortArray(chirp)
        val chirpShorts = IqooAudioRouting.monoToBottomSpeakerStereo(chirpMono)

        val minRecordBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        require(minRecordBuf > 0) { "Recording not supported at $sampleRate Hz" }
        val recordBufSize = maxOf(minRecordBuf * 2, 8192 * 2)

        var recorder: AudioRecord? = null
        var player: AudioTrack? = null
        val heldEffects = mutableListOf<AudioEffect>()
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val previousMode = audioManager.mode
        @Suppress("DEPRECATION")
        val previousSpeaker = audioManager.isSpeakerphoneOn
        val playbackError = AtomicReference<Exception?>(null)
        var focusRequest: AudioFocusRequest? = null

        try {
            // Bottom loudspeaker + bottom mic only — never earpiece / top array
            audioManager.mode = AudioManager.MODE_NORMAL
            IqooAudioRouting.clearEarpieceRoute(audioManager)
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true
            focusRequest = requestPlaybackFocus(audioManager)

            recorder = createRecorder(sampleRate, recordBufSize)
            heldEffects += holdEffectsDisabled(recorder.audioSessionId)

            player = createPlayer(sampleRate)
            routeBottomHardware(audioManager, player, recorder)
            try {
                player.setVolume(1.0f)
            } catch (_: Exception) {
            }
            IqooAudioRouting.muteTopSpeaker(player)

            val recordedShorts = ShortArray(totalRecordSamples)
            var totalRead = 0
            val writeChunk = (IqooDeviceProfile.PLAY_WRITE_CHUNK * 2).let { it - it % 2 }

            val playThread = Thread({
                try {
                    var offset = 0
                    while (offset < chirpShorts.size && playbackError.get() == null) {
                        var chunk = minOf(writeChunk, chirpShorts.size - offset)
                        if (chunk % 2 != 0) chunk -= 1
                        if (chunk <= 0) break
                        val written = player.write(chirpShorts, offset, chunk)
                        if (written < 0) {
                            throw IllegalStateException("AudioTrack write failed (code $written)")
                        }
                        offset += written
                    }
                } catch (e: Exception) {
                    playbackError.set(e)
                }
            }, "aquascope-chirp-play").also { it.start() }

            recorder.startRecording()
            player.play()

            while (totalRead < totalRecordSamples) {
                playbackError.get()?.let { throw it }
                val read = recorder.read(
                    recordedShorts, totalRead,
                    minOf(4096, totalRecordSamples - totalRead)
                )
                if (read > 0) {
                    if (onAmplitude != null) {
                        var sum = 0.0
                        val start = totalRead
                        val end = totalRead + read
                        for (i in start until end) {
                            val s = recordedShorts[i] / 32768.0
                            sum += s * s
                        }
                        onAmplitude(kotlin.math.sqrt(sum / read).toFloat())
                    }
                    totalRead += read
                } else {
                    throw IllegalStateException("Microphone read failed (code $read)")
                }
            }

            playThread.join(3000)
            playbackError.get()?.let { throw it }

            val minAcceptable = (chirpDurationSec * sampleRate).toInt()
            require(totalRead >= minAcceptable) {
                "Capture too short ($totalRead samples, need ≥ $minAcceptable)"
            }

            val recorded = DoubleArray(totalRead) {
                recordedShorts[it].toDouble() / Short.MAX_VALUE
            }
            CaptureResult(recorded, chirp, sampleRate)
        } finally {
            abandonPlaybackFocus(audioManager, focusRequest)
            try {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = previousSpeaker
                audioManager.mode = previousMode
            } catch (_: Exception) {
            }
            try {
                recorder?.run {
                    if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop()
                    release()
                }
            } catch (_: Exception) {
            }
            try {
                player?.run {
                    if (playState == AudioTrack.PLAYSTATE_PLAYING) stop()
                    flush()
                    release()
                }
            } catch (_: Exception) {
            }
            heldEffects.forEach { effect ->
                try {
                    effect.release()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun requestPlaybackFocus(audioManager: AudioManager): AudioFocusRequest? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener { }
                    .build()
                audioManager.requestAudioFocus(req)
                req
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun abandonPlaybackFocus(audioManager: AudioManager, request: AudioFocusRequest?) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && request != null) {
                audioManager.abandonAudioFocusRequest(request)
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (_: Exception) {
        }
    }

    /** Prefer 48 kHz first — native rate on Snapdragon Hi-Res / iQOO 15. */
    private fun pickSampleRate(): Int {
        for (rate in IqooDeviceProfile.SAMPLE_RATE_CANDIDATES) {
            val playOk = AudioTrack.getMinBufferSize(
                rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
            val recOk = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (playOk > 0 && recOk > 0) return rate
        }
        throw IllegalStateException(
            "No sample rate in ${IqooDeviceProfile.SAMPLE_RATE_CANDIDATES.joinToString()} Hz supports both playback and recording"
        )
    }

    private fun createPlayer(sampleRate: Int): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        require(minBuf > 0) { "Playback not supported at $sampleRate Hz" }
        val streamBuf = maxOf(minBuf * 4, 8192 * 4)

        val attempts = listOf(
            {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build()
                    )
                    .setBufferSizeInBytes(streamBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
                    .build()
            },
            {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build()
                    )
                    .setBufferSizeInBytes(streamBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            },
            {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    streamBuf,
                    AudioTrack.MODE_STREAM
                )
            }
        )

        var lastError: Exception? = null
        for (factory in attempts) {
            try {
                val track = factory()
                if (track.state == AudioTrack.STATE_INITIALIZED) return track
                track.release()
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IllegalStateException("AudioTrack failed to initialize", lastError)
    }

    private fun routeBottomHardware(
        audioManager: AudioManager,
        player: AudioTrack,
        recorder: AudioRecord
    ) {
        val speaker = IqooAudioRouting.pickBottomSpeaker(audioManager)
        val mic = IqooAudioRouting.pickBottomMic(audioManager)
        IqooAudioRouting.pinPlayer(player, speaker)
        IqooAudioRouting.pinRecorder(recorder, mic)
    }

    private fun createRecorder(sampleRate: Int, recordBufSize: Int): AudioRecord {
        var lastError: Exception? = null
        for (source in preferredAudioSources()) {
            try {
                val recorder = AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(recordBufSize)
                    .build()
                if (recorder.state == AudioRecord.STATE_INITIALIZED) return recorder
                recorder.release()
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IllegalStateException("Could not initialize microphone capture", lastError)
    }

    /** Primary / unprocessed sources map to the bottom mic; skip camcorder and voice arrays. */
    private fun preferredAudioSources(): List<Int> = listOf(
        MediaRecorder.AudioSource.UNPROCESSED,
        MediaRecorder.AudioSource.MIC,
        MediaRecorder.AudioSource.DEFAULT
    )

    private fun holdEffectsDisabled(sessionId: Int): List<AudioEffect> {
        val held = mutableListOf<AudioEffect>()
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(sessionId)?.also {
                    it.enabled = false
                    held += it
                }
            }
        } catch (_: Exception) {
        }
        try {
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(sessionId)?.also {
                    it.enabled = false
                    held += it
                }
            }
        } catch (_: Exception) {
        }
        try {
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl.create(sessionId)?.also {
                    it.enabled = false
                    held += it
                }
            }
        } catch (_: Exception) {
        }
        return held
    }
}
