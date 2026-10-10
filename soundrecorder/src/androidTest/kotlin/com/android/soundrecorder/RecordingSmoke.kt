package com.android.soundrecorder

import android.app.Activity
import android.app.Instrumentation
import android.content.*
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 原生设备检查：真实编码器、暂停时长、发布失败保留草稿和文件操作。无测试框架。 */
class RecordingSmoke : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        var activity: Activity? = null
        var libraryActivity: Activity? = null
        var bound = false
        var service: SoundRecorderService? = null
        var connected = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = (binder as SoundRecorderService.LocalBinder).service
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        val store = RecordingStore(targetContext)
        val created = mutableListOf<RecordingEntry>()
        val output = Bundle()
        var resultCode = Activity.RESULT_CANCELED
        try {
            check(store.recoverDrafts().isEmpty()) { "Smoke requires no existing unsaved recordings; save them first." }
            activity = startActivitySync(Intent(targetContext, SoundRecorder::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            runOnMainSync { bound = targetContext.bindService(Intent(targetContext, SoundRecorderService::class.java), connection, Context.BIND_AUTO_CREATE) }
            check(connected.await(10, TimeUnit.SECONDS)) { "Service did not bind" }
            val recorder = checkNotNull(service)
            val prefix = "smoke-${System.currentTimeMillis()}"
            for (format in RecordingFormat.entries) {
                runOnMainSync {
                    targetContext.startForegroundService(Intent(targetContext, SoundRecorderService::class.java))
                    recorder.start(format)
                }
                await("${format.label} start") { snapshot(recorder).durationMillis >= 1_200 }
                runOnMainSync { recorder.pause() }
                val paused = snapshot(recorder)
                check(paused.state == RecordingState.PAUSED) { "$format did not pause: $paused" }
                SystemClock.sleep(500)
                check(snapshot(recorder).durationMillis == paused.durationMillis) { "$format includes pause time" }
                runOnMainSync { recorder.resume() }
                await("${format.label} resume") { snapshot(recorder).durationMillis >= paused.durationMillis + 1_200 }
                runOnMainSync { recorder.stop() }
                await("${format.label} finish") { snapshot(recorder).state == RecordingState.READY }
                val done = snapshot(recorder)
                val draft = checkNotNull(done.draft)
                check(done.durationMillis in 2_000..4_500) { "$format wrong duration: $done" }
                if (format == RecordingFormat.WAV) {
                    RandomAccessFile(draft.file, "r").use { file ->
                        val header = ByteArray(44)
                        file.readFully(header)
                        val bytes = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                        check(String(header, 0, 4, Charsets.US_ASCII) == "RIFF") { "WAV is not RIFF" }
                        check(String(header, 8, 4, Charsets.US_ASCII) == "WAVE")
                        check(bytes.getInt(4).toLong() + 8 == file.length()) { "RIFF size does not match PCM" }
                        check(bytes.getInt(40).toLong() + 44 == file.length()) { "PCM length is wrong" }
                        check(bytes.getInt(24) == 48_000 && bytes.getShort(34).toInt() == 16)
                        check(bytes.getInt(40).toLong() / 96 == done.durationMillis) { "WAV duration does not match samples" }
                    }
                }
                val entry = store.publish(draft, "$prefix-${format.extension}", done.durationMillis)
                created += entry
                check(draft.file.exists()) { "Publish removed source before clearDraft" }
                check(runCatching { store.publish(draft, "$prefix-${format.extension}", done.durationMillis) }.isFailure) { "Duplicate publish overwrote recording" }
                check(draft.file.exists()) { "Failed publish lost source" }
                check(runCatching { store.publish(draft, "../escape", done.durationMillis) }.isFailure) { "Path traversal accepted" }
                runOnMainSync { recorder.clearDraft(draft) }
                check(!draft.file.exists()) { "Successful save did not clean draft" }
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(targetContext, entry.uri, null)
                    check(extractor.trackCount == 1) { "$format missing audio track" }
                    val audio = extractor.getTrackFormat(0)
                    val expected = when (format) {
                        RecordingFormat.AAC -> "audio/mp4a-latm"
                        RecordingFormat.AMR, RecordingFormat.THREE_GPP -> "audio/3gpp"
                        RecordingFormat.WAV -> "audio/raw"
                    }
                    check(audio.getString(MediaFormat.KEY_MIME) == expected) { "$format wrong codec: $audio" }
                } finally { extractor.release() }
                val metadata = MediaMetadataRetriever()
                try {
                    metadata.setDataSource(targetContext, entry.uri)
                    val duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                    check(duration in 2_000..4_500) { "$format is not playable: $duration" }
                } finally { metadata.release() }
                val renamed = store.rename(entry, "$prefix-renamed-${format.extension}")
                created[created.lastIndex] = renamed
                check(store.list().any { it.uri == renamed.uri && it.name == renamed.name }) { "Rename did not appear in library" }
                store.delete(renamed)
                created.removeAt(created.lastIndex)
                check(store.list().none { it.uri == renamed.uri }) { "Deleted recording remains in library" }
            }
            // 走实际按钮、命名对话框和保存回调，捕获服务测试无法发现的界面回归。
            val screen = checkNotNull(activity)
            await("Previous dialogs closed") { findEditable(uiAutomation.rootInActiveWindow) == null }
            await("Enabled format menu") { invokeMenuActionSync(screen, R.id.menu_item_filetype, 0) }
            activate(targetContext.getString(R.string.format_setting_wav_item))
            clickScreen(screen, R.string.ui_record)
            await("UI recording") { snapshot(recorder).durationMillis >= 3_000 }
            clickScreen(screen, R.string.ui_pause)
            await("UI pause") { snapshot(recorder).state == RecordingState.PAUSED }
            val uiPaused = snapshot(recorder).durationMillis
            SystemClock.sleep(500)
            check(snapshot(recorder).durationMillis == uiPaused) { "UI pause kept recording" }
            clickScreen(screen, R.string.ui_resume)
            await("UI resume") { snapshot(recorder).state == RecordingState.RECORDING }
            clickScreen(screen, R.string.ui_stop)
            await("UI stop") { snapshot(recorder).state == RecordingState.READY }
            await("Name dialog") {
                findEditable(uiAutomation.rootInActiveWindow) != null
            }
            val root = checkNotNull(uiAutomation.rootInActiveWindow)
            val input = checkNotNull(findEditable(root))
            val uiName = "$prefix-ui"
            check(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, uiName)
            }))
            val confirm = root.findAccessibilityNodeInfosByText(targetContext.getString(R.string.rename_dialog_save)).last { it.isEnabled && it.isClickable }
            val rotation = addMonitor(SoundRecorder::class.java.name, null, false)
            val libraryMonitor = addMonitor(FileListActivity::class.java.name, null, false)
            check(confirm.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            runOnMainSync { screen.recreate() }
            val rotated = checkNotNull(rotation.waitForActivityWithTimeout(10_000)) { "Activity did not recreate" }
            removeMonitor(rotation)
            activity = rotated
            val libraryScreen = checkNotNull(libraryMonitor.waitForActivityWithTimeout(10_000)) { "Save did not open original recording list" }
            removeMonitor(libraryMonitor)
            libraryActivity = libraryScreen
            await("UI save") { snapshot(recorder).state == RecordingState.IDLE }
            val uiSaved = store.list().single { it.name.startsWith(uiName) }
            created += uiSaved
            check(uiSaved.durationMillis >= 1_000) { "UI save lost recording duration: $uiSaved; ${snapshot(recorder)}" }
            check(RecordingFormat.fromMime(uiSaved.mimeType) == RecordingFormat.WAV) { "Format menu recorded the wrong format: $uiSaved" }
            check(snapshot(recorder).saved?.uri == uiSaved.uri) { "Save result disappeared after rotation" }
            await("Saved row") {
                uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(uiName)?.isNotEmpty() == true
            }
            val row = checkNotNull(uiAutomation.rootInActiveWindow).findAccessibilityNodeInfosByText(uiName).first()
            var clickable: AccessibilityNodeInfo? = row
            while (clickable?.isClickable == false) clickable = clickable.parent
            check(clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "Saved recording row did not play" }
            val seekLabel = targetContext.getString(R.string.ui_seek)
            fun progress(): Float = uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(seekLabel)?.firstOrNull()?.rangeInfo?.current ?: 0f
            await("Playback decoder progress") { progress() > 0 }
            clickScreen(libraryScreen, R.string.ui_play)
            SystemClock.sleep(250)
            val pausedProgress = progress()
            SystemClock.sleep(500)
            check(progress() == pausedProgress) { "Playback kept advancing after pause" }
            val slider = checkNotNull(uiAutomation.rootInActiveWindow).findAccessibilityNodeInfosByText(seekLabel).first()
            check(slider.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, Bundle().apply {
                putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 1_500f)
            }))
            clickScreen(libraryScreen, R.string.ui_play)
            await("Accessible playback seek") { progress() > 1_500 }
            // 操作原版长按工具栏，验证界面而非只调用文件存储 API。
            activate(uiName, longClick = true)
            awaitSelection()
            activate(targetContext.getString(R.string.action_mode_menu_edit), menu = true)
            await("UI rename dialog") { findEditable(uiAutomation.rootInActiveWindow) != null }
            val renamedName = "$uiName-renamed"
            check(checkNotNull(findEditable(uiAutomation.rootInActiveWindow)).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, renamedName)
            }))
            activate(targetContext.getString(R.string.rename_dialog_save))
            await("UI rename") { store.list().any { it.uri == uiSaved.uri && it.name.startsWith(renamedName) } }
            activate(renamedName, longClick = true)
            awaitSelection()
            activate(targetContext.getString(R.string.action_mode_menu_delete), menu = true)
            activate(targetContext.getString(R.string.delete_dialog_confirm))
            await("UI deletion") { store.list().none { it.uri == uiSaved.uri } }
            created.removeAll { it.uri == uiSaved.uri }
            // 开始命令先入服务队列，界面立刻关闭；极小上限还覆盖启动期终止事件。
            runOnMainSync {
                targetContext.startForegroundService(Intent(targetContext, SoundRecorderService::class.java)
                    .setAction(SoundRecorderService.ACTION_START)
                    .putExtra("format", RecordingFormat.WAV.name)
                    .putExtra("max_bytes", 1_044L))
                libraryScreen.finish()
                rotated.finish()
            }
            await("Background command limit") { snapshot(recorder).state == RecordingState.READY }
            val limited = snapshot(recorder)
            check(limited.draft?.file?.length() == 1_044L) { "WAV exceeded requested byte limit: $limited" }
            check(limited.durationMillis == 10L) { "Short recording terminal event was lost: $limited" }
            await("Activity closed") { rotated.isDestroyed }
            await("Library closed") { libraryScreen.isDestroyed }
            // 保存时无任何绑定客户端，结果必须留给同一个请求在返回后领取。
            val savedToken = "$prefix-background-owner"
            runOnMainSync {
                recorder.save("$prefix-background", savedToken)
                targetContext.unbindService(connection)
                bound = false
            }
            await("Unbound publication") { store.list().any { it.name.startsWith("$prefix-background") } }
            val backgroundSaved = store.list().single { it.name.startsWith("$prefix-background") }
            created += backgroundSaved
            connected = CountDownLatch(1)
            runOnMainSync { bound = targetContext.bindService(Intent(targetContext, SoundRecorderService::class.java), connection, Context.BIND_AUTO_CREATE) }
            check(connected.await(10, TimeUnit.SECONDS)) { "Service did not rebind after background save" }
            await("Background save result") { snapshot(checkNotNull(service)).saved?.uri == backgroundSaved.uri }
            val returned = snapshot(checkNotNull(service))
            check(returned.saved?.uri == backgroundSaved.uri && returned.savedFor == savedToken) { "Background save lost its owner result: $returned" }
            runOnMainSync { checkNotNull(service).acknowledgeSaved(savedToken) }
            // 立即取消尚在后台准备的麦克风，不允许取消流程留下不可终结的会话。
            activity = startActivitySync(Intent(targetContext, SoundRecorder::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            runOnMainSync {
                targetContext.startForegroundService(Intent(targetContext, SoundRecorderService::class.java))
                checkNotNull(service).start(RecordingFormat.WAV)
                checkNotNull(service).stop()
            }
            await("Startup cancellation") { snapshot(checkNotNull(service)).state in setOf(RecordingState.IDLE, RecordingState.READY) }
            runOnMainSync { checkNotNull(service).discard() }
            // WAV 崩溃恢复按实际偶数字节修复头部，不截断已落盘音频。
            val recovered = store.createDraft(RecordingFormat.WAV)
            try {
                recovered.file.writeBytes(ByteArray(44 + 96_001))
                val result = AudioCapture.recoverDraft(recovered)
                check(result.playable && result.durationMillis == 1_000L)
                check(recovered.file.length() == 96_044L)
            } finally { recovered.file.delete() }
            output.putString("stream", "PASS: four codecs, pause/resume, transactional save, collisions, rename/delete, WAV recovery, original UI rotation/playback/seek/rename/delete, background save/start/cancel and byte limit\n")
            resultCode = Activity.RESULT_OK
        } catch (failure: Throwable) {
            output.putString("stream", "FAIL: ${failure.stackTraceToString()}\n")
        } finally {
            created.forEach { runCatching { store.delete(it) } }
            runOnMainSync {
                service?.discard()
                if (bound) targetContext.unbindService(connection)
                activity?.finish()
                libraryActivity?.finish()
            }
        }
        finish(resultCode, output)
    }
    private fun snapshot(service: SoundRecorderService): RecordingSnapshot {
        var result = RecordingSnapshot()
        runOnMainSync { result = service.snapshot() }
        return result
    }
    private fun await(label: String, predicate: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 10_000
        while (!predicate()) {
            check(SystemClock.elapsedRealtime() < until) { "Timeout: $label\n${describe(uiAutomation.rootInActiveWindow)}" }
            SystemClock.sleep(25)
        }
    }

    private fun describe(node: AccessibilityNodeInfo?): String {
        if (node == null) return "<no active window>"
        return "${node.className}: text=${node.text}, description=${node.contentDescription}, clickable=${node.isClickable}, longClickable=${node.isLongClickable}, visible=${node.isVisibleToUser}\n" +
            (0 until node.childCount).joinToString("") { describe(node.getChild(it)) }
    }

    private fun clickScreen(activity: Activity, label: Int) {
        val text = targetContext.getString(label)
        await("Enabled button: $text") {
            var clicked = false
            runOnMainSync { clicked = clickButton(activity.window.decorView, text) }
            clicked
        }
    }

    private fun awaitSelection() {
        await("Contextual selection toolbar") {
            uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(targetContext.getString(R.string.action_mode_selected, 1))?.isNotEmpty() == true
        }
    }

    private fun findEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.className?.toString() == "android.widget.EditText") return node
        for (index in 0 until node.childCount) findEditable(node.getChild(index))?.let { return it }
        return null
    }

    private fun activate(label: String, longClick: Boolean = false, menu: Boolean = false) {
        await("Accessible control: $label") {
            val root = uiAutomation.rootInActiveWindow
            val matches = root?.findAccessibilityNodeInfosByText(label).orEmpty().filter { it.isVisibleToUser }
            if (menu && matches.isEmpty()) {
                val overflowId = targetContext.resources.getIdentifier("action_menu_overflow_description", "string", "android")
                if (overflowId != 0) root?.findAccessibilityNodeInfosByText(targetContext.getString(overflowId))
                    ?.firstOrNull { it.isClickable && it.isVisibleToUser }?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            matches.any { match ->
                var node: AccessibilityNodeInfo? = match
                while (node != null && if (longClick) !node.isLongClickable else !node.isClickable) node = node.parent
                node?.performAction(if (longClick) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK) == true
            }
        }
    }

    private fun clickButton(view: View, text: String): Boolean {
        if (view.isClickable && view.isEnabled && (view.contentDescription?.toString() == text || view is TextView && view.text.toString() == text)) return view.performClick()
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            if (clickButton(view.getChildAt(index), text)) return true
        }
        return false
    }
}
