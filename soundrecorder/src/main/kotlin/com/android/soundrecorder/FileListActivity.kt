package com.android.soundrecorder

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.RecoverableSecurityException
import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.AttributeSet
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** The original recording list, with scoped-storage operations and native playback. */
class FileListActivity : Activity() {
    private data class Mutation(val kind: String, val name: String?, val remaining: List<RecordingEntry>)
    // Retain only work/data, never an Activity, across configuration changes.
    private class WorkState(val store: RecordingStore) {
        val io = Executors.newSingleThreadExecutor()
        var operation: Mutation? = null
        var running = false
        var waiting: String? = null
        var failure: Throwable? = null
        var completed = false
        var changed: (() -> Unit)? = null
    }
    private data class Row(val folder: String? = null, val entry: RecordingEntry? = null) {
        val key: String get() = folder?.let { "folder:$it" } ?: requireNotNull(entry).uri.toString()
    }
    private lateinit var work: WorkState
    private val main = Handler(Looper.getMainLooper())
    private var entries = emptyList<RecordingEntry>()
    private var rows = emptyList<Row>()
    private var folder: String? = null
    private val selected = linkedSetOf<String>()
    private var mode: ActionMode? = null
    private var spinner: Spinner? = null
    private lateinit var adapter: LibraryAdapter
    private lateinit var list: ListView
    private lateinit var panel: ViewGroup
    private lateinit var playback: ImageButton
    private var controllerTouch: android.view.TouchDelegate? = null
    private lateinit var playbackTitle: TextView
    private lateinit var progressTime: TextView
    private lateinit var totalTime: TextView
    private lateinit var seek: SeekBar
    private lateinit var audio: AudioManager
    private var player: MediaPlayer? = null
    private var prepared = false
    private var playing: RecordingEntry? = null
    private var focus: AudioFocusRequest? = null
    private var visible = false
    private var seeking = false
    private var playGeneration = 0
    private var resultPending = false
    private var loadingGeneration = 0
    private val picking get() = intent.getBooleanExtra("pick_recording", false)
    private val busy get() = work.running || work.waiting != null
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val tick = object : Runnable {
        override fun run() {
            if (!visible) return
            player?.takeIf { prepared }?.let { target ->
                if (!seeking) {
                    seek.max = target.duration
                    seek.progress = target.currentPosition
                    progressTime.text = elapsed(target.currentPosition.toLong())
                }
            }
            main.postDelayed(this, 250)
        }
    }

    override fun onCreate(state: Bundle?) {
        setTheme(R.style.FileListActivity)
        super.onCreate(state)
        work = lastNonConfigurationInstance as? WorkState ?: WorkState(RecordingStore(applicationContext))
        audio = getSystemService(AudioManager::class.java)
        if (picking && intent.type != null && intent.type !in arrayOf("audio/*", "*/*") && RecordingFormat.fromMime(intent.type) == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        folder = state?.getString("folder")
        selected.addAll(state?.getStringArrayList("selected").orEmpty())
        if (lastNonConfigurationInstance == null) {
            state?.getBundle("mutation")?.let { saved ->
                runCatching { restoreMutation(saved) }.onSuccess {
                    if (saved.getString("waiting") != null) {
                        work.operation = it
                        work.waiting = saved.getString("waiting")
                    } else work.failure = IllegalStateException("File operation interrupted; check the recording list before retrying")
                }.onFailure { work.failure = it }
            }
        }
        setContentView(R.layout.file_list_activity)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.library_root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }
        panel = findViewById(R.id.player_panel)
        playback = findViewById(R.id.player_controller)
        playbackTitle = findViewById(R.id.player_title)
        progressTime = findViewById(R.id.player_progress_time)
        totalTime = findViewById(R.id.player_total_time)
        seek = findViewById(R.id.player_progress)
        playback.setOnClickListener { togglePlayback() }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) { seeking = true }
            override fun onStopTrackingTouch(bar: SeekBar) { seekTo(bar.progress); seeking = false }
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) {
                    progressTime.text = elapsed(value.toLong())
                    if (!seeking) seekTo(value)
                }
            }
        })
        // 保留原版 32dp 图标，扩展实际点击区域到 48dp，不改变播放面板尺寸。
        val controllerBounds = android.graphics.Rect()
        val previousBounds = android.graphics.Rect()
        panel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            playback.getDrawingRect(controllerBounds)
            panel.offsetDescendantRectToMyCoords(playback, controllerBounds)
            val extra = (8 * resources.displayMetrics.density).toInt()
            controllerBounds.inset(-extra, -extra)
            if (controllerBounds != previousBounds) {
                previousBounds.set(controllerBounds)
                controllerTouch = android.view.TouchDelegate(android.graphics.Rect(controllerBounds), playback)
                panel.touchDelegate = controllerTouch
            }
        }
        // Keep the original 2dp track while providing a usable touch strip above the title.
        panel.setOnTouchListener { _, event ->
            if (controllerTouch?.onTouchEvent(event) == true) true
            else if (prepared && (seeking || event.y <= 48 * resources.displayMetrics.density)) {
                val forwarded = android.view.MotionEvent.obtain(event)
                forwarded.setLocation(event.x - seek.left, seek.height / 2f)
                val handled = seek.dispatchTouchEvent(forwarded)
                forwarded.recycle()
                handled
            } else false
        }
        adapter = LibraryAdapter()
        list = findViewById<ListView>(R.id.recycler_view).apply {
            adapter = this@FileListActivity.adapter
            emptyView = this@FileListActivity.findViewById(R.id.list_empty)
            setOnItemClickListener { _, _, position, _ ->
                if (!busy && !resultPending) {
                    val row = rows[position]
                    if (mode != null) toggleSelection(row)
                    else if (row.folder != null) { folder = row.folder; rebuildRows() }
                    else row.entry?.let { if (picking) finishResult(it) else play(it) }
                }
            }
            setOnItemLongClickListener { _, _, position, _ ->
                if (!busy && !resultPending) {
                    releasePlayer()
                    selected.add(rows[position].key)
                    showSelection()
                }
                true
            }
        }
        rebuildRows()
    }

    override fun onStart() {
        super.onStart()
        if (!::work.isInitialized || !::panel.isInitialized) return
        visible = true
        work.changed = { onWorkChanged() }
        onWorkChanged()
        reload()
        main.post(tick)
    }
    override fun onStop() {
        visible = false
        main.removeCallbacks(tick)
        if (::panel.isInitialized) releasePlayer()
        if (::work.isInitialized) work.changed = null
        super.onStop()
    }
    override fun onRetainNonConfigurationInstance(): Any = work
    override fun onDestroy() {
        if (::work.isInitialized && !isChangingConfigurations) work.io.shutdown()
        super.onDestroy()
    }
    override fun onSaveInstanceState(out: Bundle) {
        out.putString("folder", folder)
        out.putStringArrayList("selected", ArrayList(selected))
        if (::work.isInitialized) work.operation?.let { out.putBundle("mutation", saveMutation(it)) }
        super.onSaveInstanceState(out)
    }

    private fun matchesMime(mime: String): Boolean {
        if (!mime.startsWith("audio/") || mime == "audio/*") return false
        val requested = if (picking) RecordingFormat.fromMime(intent.type) else null
        return requested == null || RecordingFormat.fromMime(mime) == requested
    }
    private fun reload() {
        val generation = ++loadingGeneration
        background({ work.store.list().filter { matchesMime(it.mimeType) } }) { result ->
            if (generation != loadingGeneration) return@background
            result.onSuccess {
                entries = it
                if (playing != null && entries.none { entry -> entry.uri == playing?.uri }) releasePlayer()
                rebuildRows()
            }.onFailure { showError(it) }
        }
    }
    private fun rebuildRows() {
        rows = if (folder != null) entries.filter { it.folder == folder }.map { Row(entry = it) }
        else {
            val grouped = entries.groupBy { it.folder }
            val directories = grouped.keys.filter { !isRecorderFolder(it) }.sortedWith(compareBy({ categoryOrder(it) }, { folderTitle(it) }))
            directories.map { Row(folder = it) } + entries.filter { isRecorderFolder(it.folder) }.map { Row(entry = it) }
        }
        title = folder?.let(::folderTitle) ?: getString(R.string.file_list_activity_label)
        selected.retainAll(rows.map { it.key }.toSet())
        adapter.notifyDataSetChanged()
        if (selected.isNotEmpty() && mode == null) showSelection() else updateSelection()
    }
    private fun isRecorderFolder(path: String): Boolean = path == work.store.treeUri?.toString() || path.trimEnd('/').substringAfterLast('/') == "SoundRecorder"
    private fun categoryOrder(path: String): Int = when (path.trimEnd('/').substringAfterLast('/')) { "CallRecord" -> 0; "FMRecording" -> 1; else -> 2 }
    private fun folderTitle(path: String): String {
        val name = if (path.startsWith("content://")) {
            runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(path)).substringAfterLast('/').substringAfterLast(':') }.getOrDefault(path)
        } else path.trimEnd('/').substringAfterLast('/')
        return when (name) { "CallRecord" -> getString(R.string.file_list_call); "FMRecording" -> getString(R.string.file_list_FM); else -> name.ifEmpty { path } }
    }
    private fun selectedRows() = rows.filter { it.key in selected }
    private fun selectedEntries(): List<RecordingEntry> = selectedRows().flatMap { row ->
        row.entry?.let { listOf(it) } ?: entries.filter { it.folder == row.folder }
    }.distinctBy { it.uri }
    private fun toggleSelection(row: Row) { if (!selected.add(row.key)) selected.remove(row.key); updateSelection() }
    private fun showSelection() {
        if (mode == null) mode = startActionMode(object : ActionMode.Callback {
            override fun onCreateActionMode(actionMode: ActionMode, menu: Menu): Boolean {
                menuInflater.inflate(R.menu.list_action_mode_menu, menu)
                val custom = layoutInflater.inflate(R.layout.file_list_action_mode, null)
                spinner = custom.findViewById(R.id.selection_spinner)
                actionMode.customView = custom
                if (picking) menu.add(0, PICK, 0, R.string.ui_pick_existing).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                return true
            }
            override fun onPrepareActionMode(actionMode: ActionMode, menu: Menu) = false
            override fun onActionItemClicked(actionMode: ActionMode, item: MenuItem): Boolean {
                if (busy || resultPending) return true
                when (item.itemId) {
                    R.id.action_edit -> renameSelected()
                    R.id.action_share -> shareSelected()
                    R.id.action_delete -> deleteSelected()
                    R.id.action_select_all -> selectAll()
                    PICK -> selectedRows().singleOrNull()?.entry?.let(::finishResult)
                    else -> return false
                }
                return true
            }
            override fun onDestroyActionMode(actionMode: ActionMode) {
                mode = null
                spinner = null
                selected.clear()
                adapter.notifyDataSetChanged()
            }
        })
        updateSelection()
    }
    private fun selectAll() {
        if (selected.size == rows.size) selected.clear() else selected.addAll(rows.map { it.key })
        updateSelection()
    }
    private fun updateSelection() {
        if (selected.isEmpty() && mode != null && !busy) { mode?.finish(); return }
        mode?.menu?.apply {
            findItem(R.id.action_edit).isVisible = selectedRows().singleOrNull()?.entry != null
            findItem(R.id.action_share).isEnabled = selected.isNotEmpty() && !busy && !resultPending
            findItem(R.id.action_delete).isEnabled = selected.isNotEmpty() && !busy && !resultPending
            findItem(R.id.action_edit).isEnabled = !busy && !resultPending
            findItem(PICK)?.isEnabled = selectedRows().singleOrNull()?.entry != null && !busy && !resultPending
        }
        spinner?.apply {
            onItemSelectedListener = null
            adapter = ArrayAdapter(this@FileListActivity, R.layout.spinner_dropdown_item, listOf(
                getString(R.string.action_mode_selected, selected.size),
                getString(if (selected.size == rows.size) R.string.action_mode_deselect_all else R.string.action_mode_select_all)))
            setSelection(0)
            isEnabled = !busy && !resultPending
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) { if (position == 1 && !busy) selectAll() }
                override fun onNothingSelected(parent: AdapterView<*>) = Unit
            }
        }
        adapter.notifyDataSetChanged()
    }
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, IMPORT, 0, R.string.ui_import).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }
    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { onBackPressed(); true }
        IMPORT -> { requestLibraryPermission(); true }
        else -> super.onOptionsItemSelected(item)
    }
    override fun onBackPressed() {
        if (mode != null) mode?.finish()
        else if (folder != null) { folder = null; rebuildRows() }
        else super.onBackPressed()
    }
    private fun requestLibraryPermission() {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) reload()
        else requestPermissions(arrayOf(permission), READ)
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == READ) {
            if (results.isEmpty() || results.any { it != PackageManager.PERMISSION_GRANTED }) showError(SecurityException(getString(R.string.ui_permission)))
            reload()
        }
        if (code == WRITE && work.waiting == "permission") {
            work.waiting = null
            val operation = work.operation
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED && operation != null) mutate(operation)
            else { work.operation = null; showError(SecurityException(getString(R.string.ui_permission))); updateSelection(); reload() }
        }
    }

    private fun renameSelected() {
        val entry = selectedRows().singleOrNull()?.entry ?: return
        val content = layoutInflater.inflate(R.layout.rename_dialog, null)
        val input = content.findViewById<EditText>(R.id.rename_edit_text).apply {
            setText(entry.name.substringBeforeLast('.', entry.name)); selectAll()
        }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.rename_dialog_title).setView(content)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.rename_dialog_save, null).create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(getColor(R.color.negative_button_color))
            input.requestFocusFromTouch()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isBlank() || name == "." || name == ".." || name.any { it == '/' || it == '\\' || it.isISOControl() }) input.error = getString(R.string.rename_dialog_info_null)
                else if (name.toByteArray(Charsets.UTF_8).size > 250) input.error = getString(R.string.rename_dialog_info_length_limit)
                else { dialog.dismiss(); mutate(Mutation("rename", name, listOf(entry))) }
            }
        }
        dialog.show()
    }
    private fun deleteSelected() {
        val targets = selectedEntries()
        if (targets.isEmpty()) return
        AlertDialog.Builder(this).setMessage(resources.getQuantityString(R.plurals.delete_selection_message, selected.size))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete_dialog_confirm) { _, _ -> mutate(Mutation("delete", null, targets)) }.show()
    }
    private fun mutate(operation: Mutation) {
        if (work.running) return
        releasePlayer()
        work.operation = operation
        if (Build.VERSION.SDK_INT <= 28 && operation.remaining.any { it.uri.authority == MediaStore.AUTHORITY } &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            work.waiting = "permission"
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), WRITE)
            updateSelection()
            return
        }
        work.waiting = null
        work.running = true
        updateSelection()
        val state = work
        state.io.execute {
            var completed = 0
            val outcome = runCatching {
                while (completed < operation.remaining.size) {
                    val entry = operation.remaining[completed]
                    if (operation.kind == "delete") state.store.delete(entry) else state.store.rename(entry, requireNotNull(operation.name))
                    completed++
                }
            }
            val remaining = operation.remaining.subList(completed, operation.remaining.size)
            main.post {
                state.running = false
                state.operation = if (remaining.isEmpty()) null else operation.copy(remaining = remaining)
                state.failure = outcome.exceptionOrNull()
                state.completed = outcome.isSuccess
                state.changed?.invoke()
            }
        }
    }
    private fun onWorkChanged() {
        if (!visible) return
        if (work.completed) {
            work.completed = false
            mode?.finish()
            selected.clear()
            reload()
        }
        work.failure?.let { error ->
            work.failure = null
            val operation = work.operation
            if (error is SecurityException && Build.VERSION.SDK_INT >= 29 && operation != null) {
                try {
                    val uris = operation.remaining.filter { it.uri.authority == MediaStore.AUTHORITY }.map { it.uri }
                    val sender = if (Build.VERSION.SDK_INT >= 30 && uris.isNotEmpty()) MediaStore.createWriteRequest(contentResolver, uris).intentSender
                    else (error as? RecoverableSecurityException)?.userAction?.actionIntent?.intentSender
                    if (sender != null) {
                        work.waiting = "consent"
                        startIntentSenderForResult(sender, CONSENT, null, 0, 0, 0)
                    } else { work.operation = null; showError(error) }
                } catch (failure: Exception) { work.waiting = null; work.operation = null; showError(failure) }
            } else { work.operation = null; showError(error) }
            reload()
        }
        updateSelection()
    }
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        if (code == CONSENT) {
            val operation = work.operation
            work.waiting = null
            if (result == RESULT_OK && operation != null) mutate(operation)
            else {
                work.operation = null
                if (result == RESULT_OK) showError(IllegalStateException("Missing media operation; no files changed"))
                updateSelection()
                reload()
            }
        }
    }
    private fun saveMutation(operation: Mutation) = Bundle().apply {
        putString("kind", operation.kind)
        putString("name", operation.name)
        putString("waiting", work.waiting)
        putInt("count", operation.remaining.size)
        operation.remaining.forEachIndexed { index, entry -> putBundle("entry_$index", Bundle().apply {
            putString("uri", entry.uri.toString()); putString("name", entry.name); putString("mime", entry.mimeType)
            putLong("duration", entry.durationMillis); putLong("modified", entry.modifiedSeconds); putLong("size", entry.sizeBytes); putString("folder", entry.folder)
        }) }
    }
    private fun restoreMutation(saved: Bundle): Mutation {
        val kind = requireNotNull(saved.getString("kind"))
        val name = saved.getString("name")
        val count = saved.getInt("count")
        require(kind == "delete" || kind == "rename")
        require(count > 0 && (kind != "rename" || count == 1 && !name.isNullOrBlank()))
        require(saved.getString("waiting") in arrayOf(null, "consent", "permission"))
        val remaining = (0 until count).map { index ->
            val entry = requireNotNull(saved.getBundle("entry_$index"))
            val uri = Uri.parse(requireNotNull(entry.getString("uri")))
            require(uri.scheme == ContentResolver.SCHEME_CONTENT)
            RecordingEntry(uri, requireNotNull(entry.getString("name")), requireNotNull(entry.getString("mime")),
                entry.getLong("duration"), entry.getLong("modified"), entry.getLong("size"), requireNotNull(entry.getString("folder")))
        }
        return Mutation(kind, name, remaining)
    }
    private fun shareSelected() {
        val targets = selectedEntries()
        if (targets.isEmpty()) return
        background({
            val mimes = targets.map { entry ->
                require(entry.uri.scheme == ContentResolver.SCHEME_CONTENT)
                contentResolver.openAssetFileDescriptor(entry.uri, "r")?.use { } ?: error("Recording is not readable: ${entry.name}")
                contentResolver.getType(entry.uri)?.takeIf { it.startsWith("audio/") && it != "audio/*" } ?: error(getString(R.string.error_app_unsupported))
            }
            val uris = ArrayList(targets.map { it.uri })
            Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
                type = mimes.distinct().singleOrNull() ?: "audio/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.first()) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                clipData = ClipData("Recordings", mimes.distinct().toTypedArray(), ClipData.Item(uris.first())).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            }
        }) { result -> result.onSuccess { send -> runCatching { startActivity(Intent.createChooser(send, getString(R.string.share_title))) }.onFailure(::showError) }.onFailure(::showError) }
    }
    private fun finishResult(entry: RecordingEntry) {
        if (resultPending || busy) return
        if (entry.uri.scheme != ContentResolver.SCHEME_CONTENT || !matchesMime(entry.mimeType)) { showError(IllegalArgumentException(getString(R.string.error_app_unsupported))); return }
        resultPending = true
        updateSelection()
        background({
            val mime = contentResolver.getType(entry.uri) ?: error(getString(R.string.error_app_unsupported))
            require(matchesMime(mime)) { getString(R.string.error_app_unsupported) }
            contentResolver.openAssetFileDescriptor(entry.uri, "r")?.use { } ?: error("Recording is not readable")
            mime
        }) { result ->
            result.onSuccess { mime ->
                setResult(RESULT_OK, Intent().setDataAndType(entry.uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply {
                    clipData = ClipData("Recording", arrayOf(mime), ClipData.Item(entry.uri))
                })
                finish()
            }.onFailure { resultPending = false; updateSelection(); showError(it) }
        }
    }

    private fun play(entry: RecordingEntry) {
        if (playing?.uri == entry.uri && prepared) { togglePlayback(); return }
        releasePlayer()
        val generation = playGeneration
        playing = entry
        playbackTitle.text = entry.name
        panel.visibility = View.VISIBLE
        playback.isEnabled = false
        seek.isEnabled = false
        progressTime.text = elapsed(0)
        totalTime.text = elapsed(entry.durationMillis)
        val context = applicationContext
        work.io.execute {
            val outcome = runCatching {
                val next = MediaPlayer()
                try { next.setAudioAttributes(attributes); next.setDataSource(context, entry.uri); next }
                catch (error: Exception) { next.release(); throw error }
            }
            main.post {
                if (isDestroyed || isFinishing || !visible || generation != playGeneration) { outcome.getOrNull()?.release(); return@post }
                outcome.onSuccess { next ->
                    player = next
                    next.setOnPreparedListener {
                        if (player !== it || !visible) return@setOnPreparedListener
                        prepared = true
                        playback.isEnabled = true
                        seek.isEnabled = true
                        seek.max = it.duration
                        totalTime.text = elapsed(it.duration.toLong())
                        togglePlayback()
                    }
                    next.setOnCompletionListener {
                        abandonFocus()
                        it.seekTo(0)
                        seek.progress = 0
                        progressTime.text = elapsed(0)
                        updatePlayback()
                    }
                    next.setOnErrorListener { _, _, _ -> releasePlayer(); showError(IllegalStateException(getString(R.string.error_app_unsupported))); true }
                    runCatching { next.prepareAsync() }.onFailure { releasePlayer(); showError(it) }
                }.onFailure { releasePlayer(); showError(it) }
            }
        }
    }
    private fun togglePlayback() {
        val target = player?.takeIf { prepared } ?: return
        runCatching {
            if (target.isPlaying) { target.pause(); abandonFocus() }
            else {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener { change ->
                        if (change < 0 && prepared) {
                            runCatching { player?.pause() }
                            abandonFocus()
                            updatePlayback()
                        }
                    }.build()
                if (audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    focus = request
                    target.start()
                } else showError(IllegalStateException(getString(R.string.error_record_interrupted)))
            }
            updatePlayback()
        }.onFailure { releasePlayer(); showError(it) }
    }
    private fun seekTo(position: Int) {
        player?.takeIf { prepared }?.let { target -> runCatching { target.seekTo(position) }.onFailure { releasePlayer(); showError(it) } }
    }
    private fun updatePlayback() {
        val active = player?.let { prepared && it.isPlaying } == true
        playback.setImageResource(if (active) R.drawable.player_panel_pause else R.drawable.player_panel_play)
        playback.contentDescription = getString(R.string.ui_play)
        adapter.notifyDataSetChanged()
    }
    private fun abandonFocus() { focus?.let(audio::abandonAudioFocusRequest); focus = null }
    private fun releasePlayer() {
        playGeneration++
        prepared = false
        player?.release()
        player = null
        playing = null
        abandonFocus()
        seeking = false
        seek.progress = 0
        panel.visibility = View.GONE
        updatePlayback()
    }
    private fun <T> background(operation: () -> T, completion: (Result<T>) -> Unit) {
        work.io.execute {
            val outcome = runCatching(operation)
            main.post { if (!isDestroyed && !isFinishing) completion(outcome) }
        }
    }
    private fun showError(error: Throwable) { Toast.makeText(this, getString(R.string.ui_error, error.message ?: error.javaClass.simpleName), Toast.LENGTH_LONG).show() }
    private fun elapsed(millis: Long): String {
        val seconds = millis.coerceAtLeast(0) / 1000
        return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        else String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
    }
    private inner class LibraryAdapter : BaseAdapter() {
        private val dates = SimpleDateFormat(getString(if (android.text.format.DateFormat.is24HourFormat(this@FileListActivity)) R.string.list_item_date_modified_format else R.string.list_item_date_modified_other_format).trim(), Locale.getDefault())
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position].folder != null) 0 else 1
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = rows[position]
            val view = convertView ?: layoutInflater.inflate(if (row.folder != null) R.layout.file_list_item_folder else R.layout.file_list_item_recording, parent, false)
            view.findViewById<TextView>(R.id.list_item_title).text = row.folder?.let(::folderTitle) ?: row.entry?.name
            view.isSelected = row.key in selected
            view.findViewById<CheckBox>(R.id.list_check_box).apply {
                visibility = if (mode != null) View.VISIBLE else View.INVISIBLE
                isChecked = row.key in selected
            }
            row.entry?.let { entry ->
                view.findViewById<TextView>(R.id.list_item_date_modified).text = dates.format(Date(entry.modifiedSeconds * 1000))
                view.findViewById<TextView>(R.id.list_item_duration).text = elapsed(entry.durationMillis)
                view.findViewById<RecordingWaveIndicator>(R.id.list_wave_indicator).apply {
                    visibility = if (mode == null && playing?.uri == entry.uri && prepared) View.VISIBLE else View.GONE
                    active = visibility == View.VISIBLE && player?.isPlaying == true
                }
            }
            return view
        }
    }
    companion object {
        private const val READ = 11
        private const val WRITE = 12
        private const val CONSENT = 21
        private const val IMPORT = 1001
        private const val PICK = 1002
    }
}

/** Four bars match the legacy playing/paused row indicator without a custom widget dependency. */
class RecordingWaveIndicator(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val activated: Int
    private val normal: Int
    var active = false
        set(value) { if (field != value) { field = value; invalidate() } }
    init {
        val color = android.util.TypedValue()
        check(context.theme.resolveAttribute(android.R.attr.colorControlActivated, color, true))
        activated = if (color.resourceId != 0) context.getColor(color.resourceId) else color.data
        check(context.theme.resolveAttribute(android.R.attr.colorControlNormal, color, true))
        normal = if (color.resourceId != 0) context.getColor(color.resourceId) else color.data
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = if (active) activated else normal
        val barWidth = width / 5.5f
        val phase = SystemClock.uptimeMillis() / 120L
        for (index in 0..3) {
            val fraction = if (active) (2 + (phase + index * 3) % 9) / 12f else 1f / 12f
            val left = index * barWidth * 1.5f
            canvas.drawRect(left, height * (1 - fraction), left + barWidth, height.toFloat(), paint)
        }
        if (active && isShown) postInvalidateDelayed(120)
    }
}
