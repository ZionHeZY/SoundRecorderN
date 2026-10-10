package com.android.soundrecorder

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.AttributeSet
import android.text.TextPaint
import android.text.TextUtils
import android.view.*
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** 沿用旧版 XML、主题、图标和双页面交互；录音及保存归服务所有。 */
class SoundRecorder : Activity() {
    private lateinit var store: RecordingStore
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var service: SoundRecorderService? = null
    private var bound = false
    private var visible = false
    private var busy = false
    private var format = RecordingFormat.AAC
    private var external = false
    private var lastError: String? = null
    private var lastSavedUri: String? = null
    private var resultToken = UUID.randomUUID().toString()
    private var resultPending = false
    private var lastSecond = -1L
    private var promptedDraft: String? = null
    private var saveDialog: AlertDialog? = null
    private var dialogDraft: String? = null
    private var lastState: RecordingState? = null
    private var lastBusy = false
    private var pendingStorage: Pair<Uri?, Int>? = null
    private lateinit var timer: TextView
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var record: ImageButton
    private lateinit var stop: ImageButton
    private lateinit var library: ImageButton
    private lateinit var waveform: Waveform

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as SoundRecorderService.LocalBinder).service
            render(service!!.snapshot())
            pendingStorage?.let { (uri, flags) -> pendingStorage = null; changeStorage(uri, flags) }
            recoverDraft()
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!visible) return
            service?.snapshot()?.let(::render)
            main.postDelayed(this, 100)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        store = RecordingStore(applicationContext)
        external = intent.action == MediaStore.Audio.Media.RECORD_SOUND_ACTION || intent.action == Intent.ACTION_GET_CONTENT
        val requested = intent.type
        if (external && requested != null && requested !in arrayOf("audio/*", "*/*") && RecordingFormat.fromMime(requested) == null) {
            setResult(RESULT_CANCELED); finish(); return
        }
        format = RecordingFormat.fromMime(requested) ?: run {
            val old = getSharedPreferences("storage_Path", MODE_PRIVATE).getString("requestedType", null)
            val saved = getSharedPreferences("recorder_settings", MODE_PRIVATE).getString("format", null)
            RecordingFormat.entries.firstOrNull { it.name == saved } ?: RecordingFormat.fromMime(old) ?: RecordingFormat.AAC
        }
        state?.getString("format")?.let { saved -> RecordingFormat.entries.firstOrNull { it.name == saved }?.let { format = it } }
        lastSavedUri = state?.getString("last_saved_uri")
        lastError = state?.getString("last_error")
        resultToken = state?.getString("result_token") ?: resultToken
        promptedDraft = state?.getString("prompted_draft")
        state?.getBundle("storage_change")?.let { pendingStorage = it.getString("uri")?.let(Uri::parse) to it.getInt("flags") }
        setContentView(R.layout.main)
        val root = findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }
        timer = findViewById(R.id.timerView)
        status = findViewById(R.id.stateMessage2)
        detail = findViewById(R.id.stateMessage1)
        record = findViewById(R.id.recordButton)
        stop = findViewById(R.id.stopButton)
        library = findViewById(R.id.listButton)
        waveform = findViewById(R.id.voicLine)
        waveform.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        record.setOnClickListener { onRecord() }
        stop.setOnClickListener { service?.stop() }
        stop.contentDescription = getString(R.string.ui_stop)
        library.contentDescription = getString(R.string.file_list_activity_label)
        library.setOnClickListener { openLibrary() }
        render(RecordingSnapshot())
    }

    override fun onStart() {
        super.onStart()
        visible = true
        bound = bindService(Intent(this, SoundRecorderService::class.java), connection, BIND_AUTO_CREATE)
        main.post(tick)
    }
    override fun onStop() {
        visible = false
        main.removeCallbacks(tick)
        if (bound) unbindService(connection)
        bound = false
        service = null
        super.onStop()
    }
    override fun onSaveInstanceState(out: Bundle) {
        out.putString("format", format.name)
        out.putString("last_saved_uri", if (resultPending) null else lastSavedUri)
        out.putString("last_error", lastError)
        out.putString("result_token", resultToken)
        if (saveDialog == null) out.putString("prompted_draft", promptedDraft)
        pendingStorage?.let { (uri, flags) -> out.putBundle("storage_change", Bundle().apply {
            putString("uri", uri?.toString()); putInt("flags", flags)
        }) }
        super.onSaveInstanceState(out)
    }
    override fun onDestroy() { saveDialog?.dismiss(); io.shutdown(); super.onDestroy() }

    private fun onRecord() {
        val state = service?.snapshot()?.state ?: RecordingState.IDLE
        when (state) {
            RecordingState.RECORDING -> { service?.pause(); return }
            RecordingState.PAUSED -> { service?.resume(); return }
            RecordingState.READY -> { saveDraft(); return }
            RecordingState.SAVING -> return
            RecordingState.IDLE -> Unit
        }
        if (busy) return
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT <= 28 && store.treeUri == null) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.READ_EXTERNAL_STORAGE
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.POST_NOTIFICATIONS
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), 10) else beginCapture()
    }
    private fun beginCapture() {
        startForegroundService(Intent(this, SoundRecorderService::class.java).setAction(SoundRecorderService.ACTION_START)
            .putExtra("format", format.name)
            .putExtra("max_bytes", intent.getLongExtra(MediaStore.Audio.Media.EXTRA_MAX_BYTES, 0).coerceAtLeast(0))
            .putExtra("max_duration", intent.getIntExtra(MediaStore.Audio.Media.DURATION, 0).toLong().coerceAtLeast(0)))
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 10) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                (Build.VERSION.SDK_INT > 28 || store.treeUri != null ||
                    checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)) beginCapture()
            else showError(getString(R.string.ui_permission))
        }
    }

    private fun render(snapshot: RecordingSnapshot) {
        if (snapshot.error != lastError) {
            snapshot.error?.let { showError(it); promptedDraft = null }
            detail.text = snapshot.error.orEmpty()
            detail.visibility = if (snapshot.error == null) View.INVISIBLE else View.VISIBLE
            lastError = snapshot.error
        }
        if (saveDialog != null && dialogDraft != snapshot.draft?.file?.name) saveDialog?.dismiss()
        snapshot.saved?.takeIf { snapshot.savedFor == resultToken && it.uri.toString() != lastSavedUri }?.let { saved ->
            lastSavedUri = saved.uri.toString()
            if (external || intent.getBooleanExtra("exit_after_record", false)) finishResult(saved.uri)
            else {
                Toast.makeText(this, getString(R.string.file_saved_interrupt), Toast.LENGTH_SHORT).show()
                service?.acknowledgeSaved(resultToken)
                openLibrary()
            }
        }
        val state = snapshot.state
        val changed = state != lastState || busy != lastBusy
        if (changed) {
            title = getString(if (state == RecordingState.READY) R.string.message_recorded else R.string.record_your_message)
            status.setText(when (state) {
                RecordingState.IDLE -> R.string.press_record2
                RecordingState.RECORDING -> R.string.recording
                RecordingState.PAUSED -> R.string.recording_paused
                RecordingState.READY -> R.string.recording_stopped
                RecordingState.SAVING -> R.string.ui_working
            })
            record.setImageResource(if (state == RecordingState.RECORDING) R.drawable.pause else R.drawable.record)
            record.contentDescription = getString(when (state) {
                RecordingState.RECORDING -> R.string.ui_pause
                RecordingState.PAUSED -> R.string.ui_resume
                RecordingState.READY -> R.string.rename_dialog_save
                else -> R.string.ui_record
            })
            record.isEnabled = !busy && state != RecordingState.SAVING
            stop.isEnabled = !busy && (state == RecordingState.RECORDING || state == RecordingState.PAUSED)
            library.isEnabled = !busy && (state == RecordingState.IDLE || state == RecordingState.READY) && !external
            lastState = state
            lastBusy = busy
            invalidateOptionsMenu()
        }
        val seconds = snapshot.durationMillis / 1000
        if (seconds != lastSecond) { lastSecond = seconds; timer.text = elapsed(snapshot.durationMillis) }
        waveform.update(snapshot.amplitude, state)
        if (visible && state == RecordingState.READY && snapshot.draft?.file?.name != promptedDraft && !busy) saveDraft()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }
    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val state = service?.snapshot()?.state ?: RecordingState.IDLE
        menu.findItem(R.id.menu_item_filetype)?.isEnabled = !busy && state == RecordingState.IDLE && !external
        menu.findItem(R.id.menu_item_storage)?.isEnabled = !busy && (state == RecordingState.IDLE || state == RecordingState.READY)
        return super.onPrepareOptionsMenu(menu)
    }
    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.menu_item_filetype -> { chooseFormat(); true }
        R.id.menu_item_storage -> { chooseStorage(); true }
        else -> super.onOptionsItemSelected(item)
    }
    private fun chooseFormat() {
        val choices = listOf(RecordingFormat.AMR, RecordingFormat.THREE_GPP, RecordingFormat.AAC, RecordingFormat.WAV)
        val labels = intArrayOf(R.string.format_setting_amr_item, R.string.format_setting_3gpp_item, R.string.format_setting_aac_item, R.string.format_setting_wav_item)
        AlertDialog.Builder(this, R.style.RecorderDialog).setTitle(R.string.format_setting)
            .setSingleChoiceItems(labels.map(::getString).toTypedArray(), choices.indexOf(format)) { dialog, index ->
                format = choices[index]
                getSharedPreferences("recorder_settings", MODE_PRIVATE).edit().putString("format", format.name).apply()
                dialog.dismiss()
            }.show()
    }
    private fun chooseStorage() {
        AlertDialog.Builder(this, R.style.RecorderDialog).setTitle(R.string.storage_setting)
            .setSingleChoiceItems(arrayOf(getString(R.string.storage_setting_local_item), getString(R.string.storage_setting_sdcard_item)), if (store.treeUri == null) 0 else 1) { dialog, index ->
                dialog.dismiss()
                if (index == 0) changeStorage(null)
                else startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 20)
            }.show()
    }
    private fun changeStorage(uri: Uri?, flags: Int = 0) {
        val target = service
        if (target == null) { pendingStorage = uri to flags; return }
        if (busy || target.snapshot().state !in arrayOf(RecordingState.IDLE, RecordingState.READY)) { showError(getString(R.string.ui_working)); return }
        busy = true
        work({ runCatching {
            if (uri != null) {
                val grants = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                require(grants and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0) { "Access denied" }
                contentResolver.takePersistableUriPermission(uri, grants)
            }
            store.setTree(uri)
        } }) { result -> busy = false; result.onFailure { showError(it.message ?: it.javaClass.simpleName) }; service?.snapshot()?.let(::render) }
    }
    private fun recoverDraft() {
        val target = service ?: return
        if (target.snapshot().state != RecordingState.IDLE) return
        work({ store.recoverDrafts() }) { drafts ->
            if (drafts.isNotEmpty() && service?.snapshot()?.state == RecordingState.IDLE) {
                AlertDialog.Builder(this).setTitle(R.string.ui_recover)
                    .setItems(drafts.map { it.file.name }.toTypedArray()) { _, index -> service?.restoreDraft(drafts[index]) }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
        }
    }
    private fun saveDraft() {
        val snapshot = service?.snapshot() ?: return
        val draft = snapshot.draft ?: return
        if (busy || snapshot.state != RecordingState.READY || saveDialog != null) return
        if (!matchesMime(draft.format.mimeType)) { showError(getString(R.string.error_app_unsupported)); return }
        promptedDraft = draft.file.name
        val builder = AlertDialog.Builder(this)
        val content = LayoutInflater.from(builder.context).inflate(R.layout.rename_dialog, null)
        val input = content.findViewById<EditText>(R.id.rename_edit_text)
        input.setText(getString(R.string.def_save_name_prefix) + " " + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date()))
        input.selectAll()
        val dialog = builder.setTitle(R.string.save_dialog_title).setView(content)
            .setNegativeButton(R.string.discard) { _, _ -> if (service?.snapshot()?.draft == draft) service?.discard() }
            .setPositiveButton(R.string.rename_dialog_save, null).create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        saveDialog = dialog
        dialogDraft = draft.file.name
        dialog.setOnDismissListener { if (saveDialog === dialog) { saveDialog = null; dialogDraft = null } }
        dialog.setOnShowListener {
            input.requestFocusFromTouch()
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positive.setOnClickListener {
                val name = input.text.toString().trim()
                val validation = when {
                    name.isBlank() || name == "." || name == ".." || name.any { it == '/' || it == '\\' || it.isISOControl() } -> R.string.rename_dialog_info_null
                    name.toByteArray(Charsets.UTF_8).size > 180 -> R.string.rename_dialog_info_length_limit
                    else -> null
                }
                if (validation != null) {
                    input.error = getString(validation)
                    input.requestFocus()
                } else {
                    val owner = service
                    if (owner?.snapshot()?.draft == draft && owner.snapshot().state == RecordingState.READY) {
                        owner.save(name, resultToken)
                        dialog.dismiss()
                        render(owner.snapshot())
                    }
                }
            }
            input.setOnEditorActionListener { _, action, _ ->
                if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                    positive.performClick()
                    true
                } else false
            }
        }
        dialog.show()
    }
    private fun openLibrary() {
        val request = Intent(this, FileListActivity::class.java).putExtra("pick_recording", external)
        if (external) { request.type = intent.type; startActivityForResult(request, 42) } else startActivity(request)
    }
    private fun matchesMime(mime: String): Boolean {
        if (!mime.startsWith("audio/") || mime == "audio/*") return false
        val requested = if (external) RecordingFormat.fromMime(intent.type) else null
        return requested == null || RecordingFormat.fromMime(mime) == requested
    }
    private fun finishResult(uri: Uri) {
        if (resultPending || isFinishing) return
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) { showError(getString(R.string.error_app_unsupported)); return }
        resultPending = true
        work({ runCatching {
            val mime = contentResolver.getType(uri) ?: error(getString(R.string.error_app_unsupported))
            require(matchesMime(mime)) { getString(R.string.error_app_unsupported) }
            mime
        } }) { result -> result.onSuccess { mime ->
            setResult(RESULT_OK, Intent().setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply {
                clipData = ClipData("Recording", arrayOf(mime), ClipData.Item(uri))
            })
            service?.acknowledgeSaved(resultToken)
            finish()
        }.onFailure { resultPending = false; showError(it.message ?: it.javaClass.simpleName) } }
    }
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        if (code == 20 && result == RESULT_OK) data?.data?.let { changeStorage(it, data.flags) }
        if (code == 42 && result == RESULT_OK) data?.data?.let(::finishResult)
    }
    override fun onBackPressed() {
        when (service?.snapshot()?.state) {
            RecordingState.RECORDING, RecordingState.PAUSED -> service?.stop()
            RecordingState.READY -> saveDraft()
            RecordingState.SAVING -> showError(getString(R.string.ui_working))
            else -> super.onBackPressed()
        }
    }
    private fun <T> work(operation: () -> T, success: (T) -> Unit) {
        io.execute { val result = runCatching(operation); main.post {
            if (!isDestroyed && !isFinishing) result.onSuccess(success).onFailure { showError(it.message ?: it.javaClass.simpleName) }
        } }
    }
    private fun showError(message: String) { Toast.makeText(this, getString(R.string.ui_error, message), Toast.LENGTH_LONG).show() }
    private fun elapsed(millis: Long): String {
        val seconds = millis / 1000
        return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        else String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
    }

    /**
     * 红色叠层曲线恢复 ws123/VoiceLine 的三角函数几何，Copyright 2016 carlos，Apache-2.0。
     * 来源：https://github.com/ws123/VoiceLine；完整许可见 assets/licenses/VoiceLine.txt。
     * 保留 4x(1-x) 收束、1.22^layer 相位偏移和 (2*layer-15)/20 振幅权重。
     * 修改：原 20 层均匀采样为 12 层、128 点预计算，固定横向周期、复用 Path；
     * 实测峰值按 −60..0 dBFS 控制高度，时间平滑、状态冻结、减弱动态及可访问音量说明。
     * 曲线是音量反馈，不是原始 PCM、声压或声纹；静音录音为平线，待机参考曲线不超过 3dp。
     */
    class Waveform(context: Context, attrs: AttributeSet?) : View(context, attrs) {
        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = 0xffe53935.toInt()
        }
        private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xff333333.toInt()
            textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
            textAlign = Paint.Align.CENTER
        }
        private val paths = Array(12) { Path() }
        private val sampleX = FloatArray(128)
        private val sampleSin = Array(12) { FloatArray(128) }
        private val sampleCos = Array(12) { FloatArray(128) }
        private var initialized = false
        private var framePosted = false
        private var state = RecordingState.IDLE
        private var latestDb = -60f
        private var targetLevel = 0f
        private var displayLevel = 0f
        private var hasSignal = false
        private var phase = 0.0
        private var lastFrameAt = 0L
        private var lastDescriptionAt = -1000L
        private var caption = ""
        private var visibleCaption = ""
        private var chartCenter = 0f
        private var chartRadius = 0f
        private var captionBaseline = 0f
        private val frame = Runnable {
            framePosted = false
            if (canDraw() && state == RecordingState.RECORDING && motionEnabled()) {
                advanceLevel(SystemClock.uptimeMillis(), true)
                invalidate()
            }
            syncAnimation()
        }

        init {
            // View's constructor can invoke visibility overrides before Kotlin fields exist.
            initialized = true
        }

        fun update(amplitude: Int, state: RecordingState) {
            val now = SystemClock.uptimeMillis()
            val changed = this.state != state
            this.state = state
            if (changed) lastFrameAt = 0L
            if (state == RecordingState.IDLE) {
                latestDb = -60f
                targetLevel = 0f
                displayLevel = 0f
                hasSignal = false
                phase = 0.0
            } else if (state == RecordingState.RECORDING) {
                val peak = amplitude.coerceIn(0, 32767)
                latestDb = if (peak == 0) -60f else
                    (20.0 * log10(peak / 32767.0)).toFloat().coerceIn(-60f, 0f)
                targetLevel = (latestDb + 60f) / 60f
                if (targetLevel > 0f) hasSignal = true
                if (canDraw()) advanceLevel(now, motionEnabled())
            }
            var redraw = changed || state == RecordingState.RECORDING
            if (changed || caption.isEmpty() || state == RecordingState.RECORDING && now - lastDescriptionAt >= 1000L) {
                val level = if (latestDb <= -60f) "≤ −60 dBFS" else
                    String.format(Locale.getDefault(), "%d dBFS", latestDb.roundToInt())
                val label = context.getString(R.string.ui_level)
                val stateLabel = when (state) {
                    RecordingState.IDLE -> context.getString(R.string.ui_idle)
                    RecordingState.PAUSED -> context.getString(R.string.recording_paused)
                    RecordingState.READY -> context.getString(R.string.recording_stopped)
                    RecordingState.SAVING -> context.getString(R.string.ui_working)
                    RecordingState.RECORDING -> ""
                }
                val warning = if (latestDb >= -1f && state != RecordingState.IDLE)
                    context.getString(R.string.ui_level_near_full_scale) else ""
                val description = "$label · $level" +
                    (if (stateLabel.isEmpty()) "" else " · $stateLabel") +
                    (if (warning.isEmpty()) "" else " · $warning")
                if (contentDescription != description) contentDescription = description
                val newCaption = when {
                    state == RecordingState.IDLE -> label
                    !hasSignal && (state == RecordingState.READY || state == RecordingState.SAVING) -> stateLabel
                    warning.isNotEmpty() -> "$warning · $level"
                    else -> "$label · $level"
                }
                if (caption != newCaption) {
                    caption = newCaption
                    fitCaption()
                    redraw = true
                }
                lastDescriptionAt = now
            }
            syncAnimation()
            if (redraw && canDraw()) invalidate()
        }

        private fun canDraw(): Boolean = isAttachedToWindow && isShown && windowVisibility == VISIBLE

        private fun motionEnabled(): Boolean =
            Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()

        private fun advanceLevel(now: Long, movePhase: Boolean) {
            val dt = if (lastFrameAt == 0L) 33L else (now - lastFrameAt).coerceIn(0L, 100L)
            lastFrameAt = now
            val timeConstant = if (targetLevel > displayLevel) 70.0 else 250.0
            displayLevel += (targetLevel - displayLevel) * (1.0 - exp(-dt / timeConstant)).toFloat()
            if (targetLevel == 0f && displayLevel < 0.002f) displayLevel = 0f
            if (movePhase && (targetLevel > 0f || displayLevel > 0f)) {
                phase = (phase + dt * 0.0024) % (2.0 * Math.PI)
            }
        }

        private fun stopFrames() {
            removeCallbacks(frame)
            framePosted = false
            lastFrameAt = 0L
        }

        private fun syncAnimation() {
            if (!canDraw() || state != RecordingState.RECORDING) {
                stopFrames()
            } else if (!motionEnabled()) {
                // Reduced motion receives live height only through the service's 100ms snapshots.
                if (framePosted) removeCallbacks(frame)
                framePosted = false
            } else if (targetLevel > 0f || displayLevel > 0f) {
                if (!framePosted) {
                    framePosted = postDelayed(frame, 33L)
                }
            } else {
                stopFrames()
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (initialized) syncAnimation()
        }

        override fun onDetachedFromWindow() {
            if (initialized) stopFrames()
            super.onDetachedFromWindow()
        }

        override fun onVisibilityChanged(changedView: View, visibility: Int) {
            super.onVisibilityChanged(changedView, visibility)
            if (initialized) syncAnimation()
        }

        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            if (initialized) syncAnimation()
        }

        private fun fitCaption() {
            visibleCaption = TextUtils.ellipsize(caption, textPaint,
                (width - 4f * density).coerceAtLeast(0f), TextUtils.TruncateAt.END).toString()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (!initialized) return
            val metrics = textPaint.fontMetrics
            captionBaseline = h - 2f * density - metrics.descent
            val chartBottom = (captionBaseline + metrics.ascent - 8f * density).coerceAtLeast(0f)
            chartCenter = chartBottom / 2f
            chartRadius = (chartCenter - 3f * density).coerceAtLeast(0f)
            val span = (w - 4f * density).coerceAtLeast(0f)
            for (layer in paths.indices) {
                val originalLayer = (layer * 19f / (paths.size - 1)).roundToInt()
                val offset = 1.22.pow(originalLayer)
                val weight = (2f * originalLayer - 15f) / 20f / 1.15f
                for (sample in sampleX.indices) {
                    val x = sample.toFloat() / sampleX.lastIndex
                    sampleX[sample] = 2f * density + x * span
                    val angle = x * 2.25 * 2.0 * Math.PI + offset
                    val taper = 4f * x * (1f - x) * weight
                    sampleSin[layer][sample] = sin(angle).toFloat() * taper
                    sampleCos[layer][sample] = cos(angle).toFloat() * taper
                }
            }
            fitCaption()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val amplitude = if (state == RecordingState.IDLE) minOf(3f * density, chartRadius) else displayLevel * chartRadius
            val phaseSin = sin(phase).toFloat()
            val phaseCos = cos(phase).toFloat()
            paint.color = if (latestDb >= -1f && state != RecordingState.IDLE) 0xffb3262e.toInt() else 0xffe53935.toInt()
            if (amplitude == 0f) {
                paint.alpha = 100
                paint.strokeWidth = density
                canvas.drawLine(2f * density, chartCenter, width - 2f * density, chartCenter, paint)
            } else {
                for (layer in paths.indices) {
                    val path = paths[layer]
                    path.rewind()
                    for (sample in sampleX.indices) {
                        val y = chartCenter + amplitude *
                            (sampleSin[layer][sample] * phaseCos + sampleCos[layer][sample] * phaseSin)
                        if (sample == 0) path.moveTo(sampleX[sample], y) else path.lineTo(sampleX[sample], y)
                    }
                    paint.alpha = if (layer == paths.lastIndex) 230 else 32 + layer * 7
                    paint.strokeWidth = if (layer == paths.lastIndex) 1.7f * density else density
                    canvas.drawPath(path, paint)
                }
            }
            canvas.drawText(visibleCaption, width / 2f, captionBaseline, textPaint)
        }
    }
}
