package com.android.soundrecorder

import android.net.Uri
import java.io.File

/** 录音格式与持久化条目；服务、存储和界面共享的值对象。 */
enum class RecordingFormat(val extension: String, val mimeType: String, val label: String) {
    AAC("m4a", "audio/mp4", "AAC"),
    AMR("amr", "audio/amr", "AMR"),
    THREE_GPP("3ga", "audio/3gpp", "3GPP"),
    WAV("wav", "audio/wav", "WAV");

    companion object {
        fun fromMime(mime: String?): RecordingFormat? = when (mime) {
            "audio/mp4", "audio/aac_mp4" -> AAC
            "audio/amr" -> AMR
            "audio/3gpp" -> THREE_GPP
            "audio/wav", "audio/x-wav", "audio/wave_2ch_lpcm" -> WAV
            else -> null
        }
    }
}

data class RecordingDraft(val file: File, val format: RecordingFormat)

data class RecordingEntry(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val durationMillis: Long,
    val modifiedSeconds: Long,
    val sizeBytes: Long,
    val folder: String,
)

enum class RecordingState { IDLE, RECORDING, PAUSED, READY, SAVING }

data class RecordingSnapshot(
    val state: RecordingState = RecordingState.IDLE,
    val durationMillis: Long = 0,
    val amplitude: Int = 0,
    val draft: RecordingDraft? = null,
    val error: String? = null,
    val saved: RecordingEntry? = null,
    val savedFor: String? = null,
)
