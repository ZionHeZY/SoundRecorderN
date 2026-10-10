package com.android.soundrecorder

import android.Manifest
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
import kotlin.math.log10
import kotlin.math.pow
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
        waveform.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
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
        waveform.update(snapshot.amplitude, state == RecordingState.RECORDING)
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
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert).setTitle(R.string.format_setting)
            .setSingleChoiceItems(labels.map(::getString).toTypedArray(), choices.indexOf(format)) { dialog, index ->
                format = choices[index]
                getSharedPreferences("recorder_settings", MODE_PRIVATE).edit().putString("format", format.name).apply()
                dialog.dismiss()
            }.show()
    }
    private fun chooseStorage() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert).setTitle(R.string.storage_setting)
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
        val content = layoutInflater.inflate(R.layout.rename_dialog, null)
        val input = content.findViewById<EditText>(R.id.rename_edit_text)
        input.setText(getString(R.string.def_save_name_prefix) + " " + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date()))
        input.selectAll()
        val dialog = AlertDialog.Builder(this).setTitle(R.string.save_dialog_title).setView(content)
            .setNegativeButton(R.string.discard) { _, _ -> if (service?.snapshot()?.draft == draft) service?.discard() }
            .setPositiveButton(R.string.rename_dialog_save, null).create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        saveDialog = dialog
        dialogDraft = draft.file.name
        dialog.setOnDismissListener { if (saveDialog === dialog) { saveDialog = null; dialogDraft = null } }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(getColor(R.color.negative_button_color))
            input.requestFocusFromTouch()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isBlank() || name == "." || name == ".." || name.any { it == '/' || it == '\\' || it.isISOControl() } || name.toByteArray().size > 180) {
                    input.error = getString(R.string.rename_dialog_info_null)
                } else {
                    val owner = service
                    if (owner?.snapshot()?.draft == draft && owner.snapshot().state == RecordingState.READY) {
                        owner.save(name, resultToken)
                        dialog.dismiss()
                        render(owner.snapshot())
                    }
                }
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
     * 原版红色叠层声纹；固定 Path 和三角函数表，无逐帧对象分配。
     * 波形几何改写自 ws123/VoiceLine，Copyright 2016 carlos，Apache-2.0。
     * 修改：Kotlin、预计算几何、Path 复用及仅可见录音时刷新。
     * 完整许可和来源见 assets/licenses/VoiceLine.txt。
     */
    class Waveform(context: Context, attrs: AttributeSet?) : View(context, attrs) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xffff4444.toInt(); strokeWidth = 2f; style = Paint.Style.STROKE }
        private val paths = Array(20) { Path() }
        private var sine = emptyArray<FloatArray>()
        private var cosine = emptyArray<FloatArray>()
        private var envelope = FloatArray(0)
        private var phase = 0.0
        private var volume = 10f
        private var active = false
        fun update(amplitude: Int, recording: Boolean) {
            val db = if (amplitude > 100) 20 * log10(amplitude / 100.0) else 0.0
            val target = if (recording && db > 32) height * db.toFloat() / 400 else 10f
            volume += (target - volume).coerceIn(-height / 30f, height / 30f)
            if (recording) phase = (phase + 1.5) % (Math.PI * 2)
            if (recording || active) invalidate()
            active = recording
        }
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val count = (w + 1) / 2
            envelope = FloatArray(count) { val x = (w - 1 - it * 2).toFloat() / w; 4 * x * (1 - x) }
            sine = Array(20) { n -> FloatArray(count) { i -> sin((w - 1 - i * 2 - 1.22.pow(n + 1)) * Math.PI / 180).toFloat() } }
            cosine = Array(20) { n -> FloatArray(count) { i -> cos((w - 1 - i * 2 - 1.22.pow(n + 1)) * Math.PI / 180).toFloat() } }
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val center = height / 2f
            paint.alpha = 255
            paint.strokeWidth = resources.displayMetrics.density
            canvas.drawLine(0f, center, width.toFloat(), center, paint)
            paint.strokeWidth = 2f
            val sinPhase = sin(phase).toFloat()
            val cosPhase = cos(phase).toFloat()
            for (n in paths.indices) {
                val path = paths[n]
                path.rewind()
                path.moveTo(width.toFloat(), center)
                val scale = (2 * (n + 1) - 15) / 20f * volume
                for (i in envelope.indices) {
                    val y = center + scale * envelope[i] * (sine[n][i] * cosPhase - cosine[n][i] * sinPhase)
                    path.lineTo((width - 1 - i * 2).toFloat(), y)
                }
                paint.alpha = if (n == 19) 255 else n * 130 / 20
                if (paint.alpha > 0) canvas.drawPath(path, paint)
            }
        }
    }
}
