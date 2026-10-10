package com.android.soundrecorder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import java.util.concurrent.Executors

class SoundRecorderService : Service() {
    inner class LocalBinder : Binder() { val service: SoundRecorderService get() = this@SoundRecorderService }

    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var store: RecordingStore
    private lateinit var audioManager: AudioManager
    private lateinit var notifications: NotificationManager
    private lateinit var wakeLock: PowerManager.WakeLock
    private var focusRequest: AudioFocusRequest? = null
    private var observingAudio = false
    private var foreground = false
    @Volatile private var destroyed = false
    private var starting = false
    private var restoring = false
    private var stopping = false
    private var discardAfterStop = false
    @Volatile private var cancelStart = false
    private var capture: AudioCapture? = null
    private var pendingStop: Pair<AudioCapture, String>? = null
    private var state = RecordingState.IDLE
    private var draft: RecordingDraft? = null
    private var error: String? = null
    private var lastSaved: RecordingEntry? = null
    private var lastSavedFor: String? = null
    private var elapsedMillis = 0L
    private var segmentStart = 0L
    private var maxBytes = 0L
    private var maxDurationMillis = 0L

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change < 0 && (capture != null || starting)) stopWithError("录音被其他音频或通话中断，已保留可恢复的录音。")
    }

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            if (state == RecordingState.RECORDING && capture?.isSilenced() == true) {
                stopWithError("麦克风被其他应用或通话占用，录音已停止。")
            }
        }
    }

    private val startupTimeout = Runnable {
        if (capture == null && !starting) {
            leaveForeground()
            stopSelf()
        }
    }

    private val monitor = object : Runnable {
        override fun run() {
            if (capture == null || stopping) return
            try {
                if (state == RecordingState.RECORDING) {
                    when {
                        capture?.isSilenced() == true -> stopWithError("麦克风被其他应用或通话占用，录音已停止。")
                        maxDurationMillis > 0 && durationMillis() >= maxDurationMillis -> stopWithError("已达到录音时长上限。")
                        maxBytes > 0 && (draft?.file?.length() ?: 0) >= maxBytes -> stopWithError("已达到文件大小上限。")
                        availableBytes() <= STORAGE_RESERVE -> stopWithError("存储空间不足，录音已停止并保留。请释放空间后重试。")
                    }
                }
            } catch (failure: Exception) {
                stopWithError("无法检查录音存储空间：${failure.message ?: failure.javaClass.simpleName}")
            }
            if (capture != null && !stopping) handler.postDelayed(this, 250)
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = RecordingStore(this)
        audioManager = getSystemService(AudioManager::class.java)
        notifications = getSystemService(NotificationManager::class.java)
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:recording").apply {
            setReferenceCounted(false)
        }
        notifications.createNotificationChannel(NotificationChannel(CHANNEL_ID, "录音", NotificationManager.IMPORTANCE_LOW).apply {
            description = "后台录音控制"
            setSound(null, null)
        })
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stop()
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_START -> {
                val format = RecordingFormat.entries.firstOrNull { it.name == intent.getStringExtra("format") }
                if (format != null) start(format, intent.getLongExtra("max_bytes", 0), intent.getLongExtra("max_duration", 0))
                else { error = "录音格式无效。"; stopSelf() }
            }
            ACTION_RETAIN -> Unit
            else -> {
                try {
                    if (state == RecordingState.IDLE && !starting) {
                        ensureForeground()
                        handler.removeCallbacks(startupTimeout)
                        handler.postDelayed(startupTimeout, 4_000)
                    }
                } catch (failure: Exception) {
                    error = "无法启动麦克风前台服务：${failure.message ?: failure.javaClass.simpleName}"
                    releaseSessionResources()
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    fun snapshot(): RecordingSnapshot {
        checkMainThread()
        return RecordingSnapshot(state, durationMillis(), if (!stopping) capture?.amplitude() ?: 0 else 0, draft, error, lastSaved, lastSavedFor)
    }

    fun start(format: RecordingFormat, maxBytes: Long = 0, maxDurationMillis: Long = 0) {
        checkMainThread()
        if (capture != null || starting || restoring || stopping || state == RecordingState.SAVING) return
        if (draft != null) {
            error = "请先保存或丢弃尚未保存的录音。"
            leaveForeground()
            return
        }
        if (maxBytes < 0 || maxDurationMillis < 0 || (maxBytes in 1..1_023)) {
            error = "录音限制无效：文件大小至少为 1024 字节，时长不能为负数。"
            leaveForeground()
            stopSelf()
            return
        }
        error = null
        lastSaved = null
        lastSavedFor = null
        pendingStop = null
        cancelStart = false
        discardAfterStop = false
        this.maxDurationMillis = maxDurationMillis
        try {
            check(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "请授予麦克风权限。" }
            // Android 14+ 必须先进入 microphone 前台服务，再获取麦克风。
            ensureForeground()
            requestAudioFocus()
            val budget = availableBytes() - STORAGE_RESERVE
            check(budget >= 128 * 1024) { "可用空间不足，请至少释放 8 MB 后重试。" }
            this.maxBytes = if (maxBytes > 0) minOf(maxBytes, budget) else budget
            starting = true
            wakeLock.acquire()
            elapsedMillis = 0
            state = RecordingState.RECORDING
            handler.removeCallbacks(startupTimeout)
            worker.execute {
                var created: RecordingDraft? = null
                var active: AudioCapture? = null
                try {
                    val recovered = store.recoverDrafts().firstOrNull()
                    if (recovered != null) {
                        handler.post {
                            starting = false
                            releaseSessionResources()
                            state = RecordingState.IDLE
                            restoreDraft(recovered)
                            error = "发现尚未保存的录音，请先保存或丢弃。"
                            showRecoverableNotification()
                        }
                        return@execute
                    }
                    if (destroyed || cancelStart) {
                        handler.post {
                            starting = false
                            state = RecordingState.IDLE
                            discardAfterStop = false
                            releaseSessionResources()
                            stopSelf()
                        }
                        return@execute
                    }
                    created = store.createDraft(format)
                    active = AudioCapture(this, created, this.maxBytes, this.maxDurationMillis) { message ->
                        handler.post {
                            if (!destroyed && capture === active && !stopping) stopWithError(message)
                            else if (starting && !destroyed) active?.let { pendingStop = it to message }
                        }
                    }
                    // prepare、文件头和麦克风启动都在后台线程，避免卡住界面。
                    active.start()
                    val started = active
                    val file = created
                    handler.post {
                        starting = false
                        if (destroyed || cancelStart) {
                            capture = started
                            draft = file
                            stop()
                        } else beginCapture(file, started)
                    }
                } catch (failure: Exception) {
                    val failedDraft = created
                    val complete: (AudioCapture.Result?) -> Unit = { result ->
                        if (result?.playable != true && failedDraft?.format != RecordingFormat.WAV) failedDraft?.file?.delete()
                        handler.post {
                            starting = false
                            draft = failedDraft?.takeIf { it.file.exists() && it.file.length() > 0 }
                            state = if (draft != null) RecordingState.READY else RecordingState.IDLE
                            elapsedMillis = result?.durationMillis ?: 0
                            error = "无法开始录音：${failure.message ?: failure.javaClass.simpleName}"
                            releaseSessionResources()
                            if (draft != null) showRecoverableNotification() else stopSelf()
                        }
                    }
                    if (active != null) active.stop(worker) { complete(it) }
                    else { failedDraft?.file?.delete(); complete(null) }
                }
            }
        } catch (failure: Exception) {
            state = RecordingState.IDLE
            error = "无法开始录音：${failure.message ?: failure.javaClass.simpleName}"
            releaseSessionResources()
            stopSelf()
        }
    }

    private fun beginCapture(created: RecordingDraft, active: AudioCapture) {
        draft = created
        capture = active
        segmentStart = SystemClock.elapsedRealtime()
        state = RecordingState.RECORDING
        wakeLock.acquire()
        if (Build.VERSION.SDK_INT >= 29) {
            audioManager.registerAudioRecordingCallback(recordingCallback, handler)
            observingAudio = true
        }
        val terminal = pendingStop?.takeIf { it.first === active }?.second
        pendingStop = null
        if (terminal != null || active.isSilenced()) {
            stopWithError(terminal ?: "麦克风被其他应用或通话占用，录音已停止。")
            return
        }
        updateNotification()
        handler.post(monitor)
    }

    fun pause() {
        checkMainThread()
        if (state != RecordingState.RECORDING || starting || stopping) return
        try {
            capture?.pause() ?: return
            elapsedMillis = durationMillis()
            segmentStart = 0
            state = RecordingState.PAUSED
            if (wakeLock.isHeld) wakeLock.release()
            updateNotification()
        } catch (failure: Exception) {
            stopWithError("暂停录音失败：${failure.message ?: failure.javaClass.simpleName}")
        }
    }

    fun resume() {
        checkMainThread()
        if (state != RecordingState.PAUSED || stopping || capture == null) return
        try {
            ensureForeground()
            check(availableBytes() > STORAGE_RESERVE) { "存储空间不足，请释放空间。" }
            capture?.resume()
            check(capture?.isSilenced() != true) { "麦克风被其他应用或通话占用。" }
            segmentStart = SystemClock.elapsedRealtime()
            state = RecordingState.RECORDING
            wakeLock.acquire()
            updateNotification()
        } catch (failure: Exception) {
            stopWithError("恢复录音失败：${failure.message ?: failure.javaClass.simpleName}")
        }
    }

    fun stop() {
        checkMainThread()
        if (starting) {
            cancelStart = true
            return
        }
        val active = capture
        if (active == null || stopping) {
            if (active == null) { leaveForeground(); stopSelf() }
            return
        }
        elapsedMillis = durationMillis()
        segmentStart = 0
        stopping = true
        state = RecordingState.PAUSED
        handler.removeCallbacks(monitor)
        releaseSessionResources(keepNotification = true, keepWakeLock = true)
        showFinishingNotification()
        active.stop(worker) { result ->
            handler.post {
                if (wakeLock.isHeld) wakeLock.release()
                capture = null
                stopping = false
                result.durationMillis?.let { elapsedMillis = it }
                if (result.error != null) error = result.error
                if (!result.playable && draft?.format != RecordingFormat.WAV) {
                    // 仅删除已确认无效的压缩文件；有效音频一直保留到保存或主动丢弃。
                    deleteCurrentDraft()
                } else state = if (draft != null) RecordingState.READY else RecordingState.IDLE
                if (discardAfterStop) deleteCurrentDraft()
                discardAfterStop = false
                if (state == RecordingState.READY) showRecoverableNotification() else notifications.cancel(NOTIFICATION_ID)
                if (state != RecordingState.READY) stopSelf()
            }
        }
    }

    private fun stopWithError(message: String) {
        error = message
        stop()
    }

    fun discard() {
        checkMainThread()
        if (state == RecordingState.SAVING) return
        if (restoring) {
            error = "正在恢复录音，请稍后再丢弃。"
            return
        }
        if (capture != null || starting || stopping) {
            discardAfterStop = true
            stop()
        } else {
            deleteCurrentDraft()
            leaveForeground()
            stopSelf()
        }
    }

    /** 只清除已经发布的同一草稿，不能误删另一个界面创建的新录音。 */
    fun clearDraft(published: RecordingDraft) {
        checkMainThread()
        if (draft?.file != published.file || capture != null || starting || restoring || stopping || state == RecordingState.SAVING) return
        deleteCurrentDraft()
        leaveForeground()
        stopSelf()
    }

    fun save(name: String, resultToken: String) {
        checkMainThread()
        val source = draft?.takeIf { state == RecordingState.READY } ?: return
        val duration = elapsedMillis
        error = null
        state = RecordingState.SAVING
        startService(Intent(this, SoundRecorderService::class.java).setAction(ACTION_RETAIN))
        publishDetached(getString(R.string.ui_working), ongoing = true)
        worker.execute {
            val result = runCatching { store.publish(source, name, duration) }
            handler.post {
                result.onSuccess { entry ->
                    lastSaved = entry
                    lastSavedFor = resultToken
                    if (draft?.file == source.file) deleteCurrentDraft()
                    leaveForeground()
                }.onFailure {
                    state = RecordingState.READY
                    error = "保存失败，草稿已保留：${it.message ?: it.javaClass.simpleName}"
                    showRecoverableNotification()
                }
            }
        }
    }

    fun acknowledgeSaved(resultToken: String) {
        checkMainThread()
        if (lastSavedFor == resultToken && state == RecordingState.IDLE && !starting) stopSelf()
    }

    private fun deleteCurrentDraft() {
        val file = draft?.file
        if (file != null && file.exists() && !file.delete()) {
            state = RecordingState.READY
            error = "无法删除草稿文件，请检查存储空间后重试。"
            return
        }
        draft = null
        elapsedMillis = 0
        segmentStart = 0
        state = RecordingState.IDLE
    }

    fun restoreDraft(draft: RecordingDraft, durationMillis: Long = 0) {
        checkMainThread()
        if (capture != null || starting || stopping || restoring) return
        if (this.draft != null && this.draft?.file != draft.file) {
            error = "请先保存或丢弃当前草稿。"
            return
        }
        this.draft = draft
        elapsedMillis = durationMillis.coerceAtLeast(0)
        state = RecordingState.IDLE
        restoring = true
        worker.execute {
            val result = AudioCapture.recoverDraft(draft)
            handler.post {
                restoring = false
                state = RecordingState.READY
                if (durationMillis <= 0) elapsedMillis = result.durationMillis ?: 0
                if (!result.playable) error = result.error ?: "草稿无法播放，文件已保留；可以导出文件或丢弃后重录。"
            }
        }
    }

    private fun durationMillis(): Long = elapsedMillis + if (state == RecordingState.RECORDING && !starting && segmentStart != 0L) {
        (SystemClock.elapsedRealtime() - segmentStart).coerceAtLeast(0)
    } else 0

    private fun availableBytes(): Long = StatFs(filesDir.absolutePath).availableBytes

    private fun requestAudioFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener(focusListener, handler).build()
        focusRequest = request
        check(audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "麦克风正被通话或其他音频占用。" }
    }

    private fun releaseSessionResources(keepNotification: Boolean = false, keepWakeLock: Boolean = false) {
        handler.removeCallbacks(monitor)
        handler.removeCallbacks(startupTimeout)
        if (observingAudio) {
            audioManager.unregisterAudioRecordingCallback(recordingCallback)
            observingAudio = false
        }
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        if (!keepWakeLock && wakeLock.isHeld) wakeLock.release()
        if (!keepNotification) leaveForeground()
    }

    private fun ensureForeground() {
        val notification = notification()
        if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIFICATION_ID, notification)
        foreground = true
    }

    private fun updateNotification() { if (foreground) notifications.notify(NOTIFICATION_ID, notification()) }

    private fun leaveForeground() {
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        notifications.cancel(NOTIFICATION_ID)
    }

    private fun showFinishingNotification() {
        notifications.notify(NOTIFICATION_ID, notification(getString(R.string.ui_notify_finishing), ongoing = true, actions = false))
    }

    private fun showRecoverableNotification() {
        publishDetached(getString(R.string.ui_notify_ready), ongoing = false)
    }

    private fun publishDetached(text: String, ongoing: Boolean) {
        if (foreground) {
            stopForeground(STOP_FOREGROUND_DETACH)
            foreground = false
        }
        notifications.notify(NOTIFICATION_ID, notification(text, ongoing, actions = false))
    }

    private fun notification(): Notification = notification(when (state) {
        RecordingState.PAUSED -> getString(R.string.ui_notify_paused)
        RecordingState.READY -> getString(R.string.ui_notify_ready)
        RecordingState.SAVING -> getString(R.string.ui_working)
        else -> getString(R.string.ui_notify_recording)
    }, ongoing = true, actions = capture != null && !stopping)

    private fun notification(text: String, ongoing: Boolean, actions: Boolean): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(ongoing).setAutoCancel(!ongoing).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            builder.setContentIntent(PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        if (actions) {
            val paused = state == RecordingState.PAUSED
            val toggle = if (paused) ACTION_RESUME else ACTION_PAUSE
            builder.addAction(Notification.Action.Builder(null, getString(if (paused) R.string.ui_resume else R.string.ui_pause), actionIntent(toggle, 1)).build())
            builder.addAction(Notification.Action.Builder(null, getString(R.string.ui_stop), actionIntent(ACTION_STOP, 2)).build())
        }
        return builder.build()
    }

    private fun actionIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this, requestCode, Intent(this, SoundRecorderService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun checkMainThread() { check(Looper.myLooper() == Looper.getMainLooper()) { "录音服务操作必须在主线程调用。" } }

    override fun onDestroy() {
        destroyed = true
        if (capture != null || starting) stop()
        releaseSessionResources(keepNotification = state == RecordingState.READY || state == RecordingState.SAVING || stopping || starting, keepWakeLock = stopping || starting)
        // 不删除草稿；进程/服务终止后的恢复仍依赖它。
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.android.soundrecorder.STOP"
        const val ACTION_PAUSE = "com.android.soundrecorder.PAUSE"
        const val ACTION_RESUME = "com.android.soundrecorder.RESUME"
        const val ACTION_START = "com.android.soundrecorder.START"
        private const val ACTION_RETAIN = "com.android.soundrecorder.RETAIN"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 62343234
        private const val STORAGE_RESERVE = 8L * 1024 * 1024
    }
}
