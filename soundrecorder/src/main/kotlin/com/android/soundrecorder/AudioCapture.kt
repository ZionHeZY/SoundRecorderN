package com.android.soundrecorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kotlin.math.abs
import kotlin.math.min

/** 平台编码器与 PCM 写入器；所有停止操作均不在主线程等待录音线程。 */
internal class AudioCapture(
    private val context: Context,
    private val draft: RecordingDraft,
    private val maxBytes: Long,
    private val maxDurationMillis: Long,
    private val onStopRequested: (String) -> Unit,
) {
    data class Result(val playable: Boolean, val error: String? = null, val durationMillis: Long? = null)

    private var recorder: MediaRecorder? = null
    private var audioRecord: AudioRecord? = null
    private var output: RandomAccessFile? = null
    private var writer: Thread? = null
    private var recorderStarted = false
    private val gate = Object()
    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var stopping = false
    @Volatile private var epoch = 0
    @Volatile private var peak = 0
    private var finish: ((Result) -> Unit)? = null

    fun start() {
        if (draft.format == RecordingFormat.WAV) startWav() else startCompressed()
    }

    @Suppress("DEPRECATION")
    private fun startCompressed() {
        val media = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        recorder = media
        media.setAudioSource(MediaRecorder.AudioSource.MIC)
        media.setOutputFormat(when (draft.format) {
            RecordingFormat.AAC -> MediaRecorder.OutputFormat.MPEG_4
            RecordingFormat.AMR -> MediaRecorder.OutputFormat.AMR_NB
            else -> MediaRecorder.OutputFormat.THREE_GPP
        })
        media.setAudioEncoder(if (draft.format == RecordingFormat.AAC) MediaRecorder.AudioEncoder.AAC else MediaRecorder.AudioEncoder.AMR_NB)
        media.setAudioChannels(1)
        media.setAudioSamplingRate(if (draft.format == RecordingFormat.AAC) 48_000 else 8_000)
        media.setAudioEncodingBitRate(if (draft.format == RecordingFormat.AAC) 128_000 else 12_200)
        media.setOutputFile(draft.file.absolutePath)
        if (maxBytes > 0) media.setMaxFileSize(maxBytes)
        // 时长由服务的单调时钟限制，避免厂商把暂停时间计入 maxDuration。
        media.setOnErrorListener { _, what, extra ->
            if (!stopping) onStopRequested("录音设备出错 ($what/$extra)，请检查麦克风后重试。")
        }
        media.setOnInfoListener { _, what, _ ->
            if (!stopping && what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                onStopRequested("已达到文件大小或存储空间上限。")
            }
        }
        media.prepare()
        media.start()
        recorderStarted = true
        running = true
    }

    private fun startWav() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("请授予麦克风权限。")
        }
        val minimum = AudioRecord.getMinBufferSize(48_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "设备不支持 48 kHz PCM 录音。" }
        val bufferBytes = maxOf(minimum, 9_600).let { it + it % 2 }
        val audio = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(48_000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
            .setBufferSizeInBytes(bufferBytes * 2).build()
        audioRecord = audio
        check(audio.state == AudioRecord.STATE_INITIALIZED) { "无法初始化麦克风。" }
        val file = RandomAccessFile(draft.file, "rw")
        output = file
        file.setLength(44)
        val header = wavHeader(0)
        file.write(header)
        file.fd.sync()
        val samples = ShortArray(bufferBytes / 2)
        val bytes = ByteArray(bufferBytes)
        audio.startRecording()
        check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "无法开始录音，请检查麦克风权限。" }
        running = true
        writer = Thread({ writeWav(audio, file, header, samples, bytes) }, "WavRecorder").apply { start() }
    }

    private fun writeWav(audio: AudioRecord, file: RandomAccessFile, header: ByteArray, samples: ShortArray, bytes: ByteArray) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var dataBytes = 0L
        var syncedBytes = 0L
        var failure: String? = null
        var limitMessage: String? = null
        val sizeLimit = if (maxBytes > 0) (maxBytes - 44).coerceAtLeast(0) else Long.MAX_VALUE
        val durationLimit = if (maxDurationMillis > 0) {
            if (maxDurationMillis > Long.MAX_VALUE / 96) Long.MAX_VALUE else maxDurationMillis * 96
        } else Long.MAX_VALUE
        val dataLimit = min(min(sizeLimit, durationLimit), 0xffff_ffffL - 36).let { it - it % 2 }
        try {
            while (running) {
                synchronized(gate) { while (paused && running) gate.wait() }
                if (!running) break
                val readEpoch = epoch
                val count = audio.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                // 暂停会 stop AudioRecord 来打断阻塞读取，负返回值只表示这次读取作废。
                if (!running || paused || readEpoch != epoch) continue
                check(count >= 0) { "读取麦克风失败 ($count)。" }
                if (count == 0) continue
                val sampleCount = min(count.toLong(), (dataLimit - dataBytes) / 2).toInt()
                var amplitude = 0
                for (i in 0 until sampleCount) {
                    val sample = samples[i].toInt()
                    amplitude = maxOf(amplitude, abs(sample))
                    bytes[i * 2] = sample.toByte()
                    bytes[i * 2 + 1] = (sample shr 8).toByte()
                }
                val length = sampleCount * 2
                if (length > 0) {
                    file.write(bytes, 0, length)
                    dataBytes += length
                    peak = amplitude
                }
                if (dataBytes - syncedBytes >= 96_000) {
                    checkpointWav(file, header, dataBytes)
                    syncedBytes = dataBytes
                }
                if (dataBytes >= dataLimit) {
                    limitMessage = "已达到录音时长、文件大小或 WAV 格式上限。"
                    break
                }
            }
        } catch (error: Exception) {
            failure = "PCM 录音中断：${error.message ?: error.javaClass.simpleName}"
        } finally {
            running = false
            peak = 0
            try { audio.stop() } catch (_: Exception) { }
            try { audio.release() } catch (error: Exception) {
                failure = "麦克风释放失败：${error.message ?: error.javaClass.simpleName}"
            }
            try {
                file.setLength(44 + dataBytes)
                checkpointWav(file, header, dataBytes)
            } catch (error: Exception) {
                failure = "WAV 保存失败：${error.message ?: error.javaClass.simpleName}"
            } finally {
                try { file.close() } catch (error: Exception) {
                    failure = "WAV 文件关闭失败：${error.message ?: error.javaClass.simpleName}"
                }
            }
            synchronized(gate) {
                val result = Result(dataBytes > 0 && failure == null, failure, dataBytes / 96)
                completed = result
                finish?.invoke(result)
            }
            if (!stopping) onStopRequested(failure ?: limitMessage ?: "PCM 录音意外停止。")
        }
    }

    private var completed: Result? = null

    fun amplitude(): Int = if (!running || paused || stopping) 0 else {
        if (draft.format == RecordingFormat.WAV) peak else try { recorder?.maxAmplitude ?: 0 } catch (_: RuntimeException) { 0 }
    }

    fun isSilenced(): Boolean {
        if (Build.VERSION.SDK_INT < 29 || !running || paused || stopping) return false
        val config = if (draft.format == RecordingFormat.WAV) audioRecord?.activeRecordingConfiguration
            else recorder?.activeRecordingConfiguration
        return config?.isClientSilenced == true
    }

    fun pause() {
        if (draft.format == RecordingFormat.WAV) {
            paused = true
            epoch++
            audioRecord?.stop()
            peak = 0
        } else recorder?.pause()
        paused = true
    }

    fun resume() {
        if (draft.format == RecordingFormat.WAV) {
            audioRecord?.startRecording()
            check(audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "无法恢复麦克风录音。" }
        } else recorder?.resume()
        paused = false
        synchronized(gate) { gate.notifyAll() }
    }

    fun stop(executor: Executor, callback: (Result) -> Unit) {
        stopping = true
        running = false
        if (draft.format == RecordingFormat.WAV && writer != null) {
            synchronized(gate) {
                finish = callback
                completed?.let { callback(it) }
                gate.notifyAll()
            }
            // stop 唤醒阻塞 read；不在主线程 join，最终写头由录音线程负责。
            execute(executor) { try { audioRecord?.stop() } catch (_: Exception) { } }
        } else {
            execute(executor) {
                var stopError: String? = null
                try {
                    if (recorderStarted) recorder?.stop()
                } catch (_: RuntimeException) {
                    stopError = "录音过短或被中断，无法完成编码；请录制至少一秒后再停止。"
                } finally {
                    try { recorder?.release() } catch (failure: Exception) {
                        stopError = "录音设备释放失败：${failure.message ?: failure.javaClass.simpleName}"
                    }
                    recorder = null
                    if (writer == null) {
                        try { audioRecord?.release() } catch (_: Exception) { }
                        try { output?.close() } catch (_: Exception) { }
                    }
                }
                val playable = isPlayable(draft.file)
                callback(Result(playable, if (!playable) stopError ?: "录音未生成可播放的音频，请检查麦克风后重试。" else stopError))
            }
        }
    }

    companion object {
        private fun execute(executor: Executor, task: () -> Unit) {
            try {
                executor.execute(task)
            } catch (_: RejectedExecutionException) {
                Thread(task, "AudioCaptureStop").start()
            }
        }

        fun isPlayable(file: File): Boolean = duration(file) > 0

        fun duration(file: File): Long {
            if (!file.isFile || file.length() == 0L) return 0
            val metadata = MediaMetadataRetriever()
            return try {
                metadata.setDataSource(file.absolutePath)
                metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            } catch (_: Exception) { 0 } finally { try { metadata.release() } catch (_: Exception) { } }
        }

        fun recoverDraft(draft: RecordingDraft): Result {
            return try {
                if (draft.format == RecordingFormat.WAV) {
                    check(draft.file.length() >= 44) { "WAV 草稿没有完整文件头，文件已保留。" }
                    val dataBytes = (draft.file.length() - 44).coerceAtMost(0xffff_ffffL - 36).let { it - it % 2 }
                    RandomAccessFile(draft.file, "rw").use { file ->
                        file.setLength(44 + dataBytes)
                        checkpointWav(file, wavHeader(dataBytes), dataBytes)
                    }
                    Result(dataBytes > 0, if (dataBytes == 0L) "WAV 草稿没有录到音频。" else null, dataBytes / 96)
                } else {
                    val duration = duration(draft.file)
                    Result(duration > 0, if (duration <= 0) "进程中断前编码器未完成文件，草稿已保留但可能无法播放。" else null, duration)
                }
            } catch (failure: Exception) {
                Result(false, "草稿恢复失败，文件已保留：${failure.message ?: failure.javaClass.simpleName}")
            }
        }

        internal fun wavHeader(dataBytes: Long): ByteArray = ByteArray(44).also { fillWavHeader(it, dataBytes) }

        private fun fillWavHeader(header: ByteArray, dataBytes: Long) {
            require(dataBytes in 0..(0xffff_ffffL - 36) && dataBytes % 2 == 0L)
            writeLittleEndian(header, 0, 0x46464952, 4)
            writeLittleEndian(header, 4, 36 + dataBytes, 4)
            writeLittleEndian(header, 8, 0x45564157, 4)
            writeLittleEndian(header, 12, 0x20746d66, 4)
            writeLittleEndian(header, 16, 16, 4)
            writeLittleEndian(header, 20, 1, 2)
            writeLittleEndian(header, 22, 1, 2)
            writeLittleEndian(header, 24, 48_000, 4)
            writeLittleEndian(header, 28, 96_000, 4)
            writeLittleEndian(header, 32, 2, 2)
            writeLittleEndian(header, 34, 16, 2)
            writeLittleEndian(header, 36, 0x61746164, 4)
            writeLittleEndian(header, 40, dataBytes, 4)
        }

        private fun checkpointWav(file: RandomAccessFile, header: ByteArray, dataBytes: Long) {
            // 先落盘 PCM，再更新 RIFF 长度；进程被杀时仍能按文件长度恢复。
            file.fd.sync()
            fillWavHeader(header, dataBytes)
            file.seek(0)
            file.write(header)
            file.fd.sync()
            file.seek(44 + dataBytes)
        }

        private fun writeLittleEndian(bytes: ByteArray, offset: Int, value: Long, count: Int) {
            for (i in 0 until count) bytes[offset + i] = (value ushr (8 * i)).toByte()
        }
    }
}
