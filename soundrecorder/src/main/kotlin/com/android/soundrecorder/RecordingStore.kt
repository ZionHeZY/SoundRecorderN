package com.android.soundrecorder

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID

/** 所有方法均执行磁盘 I/O，调用方必须在后台串行调用。 */
class RecordingStore(context: Context) {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val preferences = this.context.getSharedPreferences("recorder_settings", Context.MODE_PRIVATE)
    private val draftsDirectory = File(this.context.filesDir, "drafts")

    val treeUri: Uri?
        get() = preferences.getString("tree_uri", null)?.let(Uri::parse)

    fun setTree(uri: Uri?) {
        if (uri != null) {
            require(DocumentsContract.isTreeUri(uri)) { "请选择一个文件夹" }
            val root = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
            query(root, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE)).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    "所选文件夹不可用"
                }
            }
        }
        val editor = preferences.edit()
        if (uri == null) editor.remove("tree_uri") else editor.putString("tree_uri", uri.toString())
        if (!editor.commit()) throw IOException("无法保存录音文件夹设置")
    }

    fun createDraft(format: RecordingFormat): RecordingDraft {
        if (!draftsDirectory.isDirectory && !draftsDirectory.mkdirs()) throw IOException("无法创建录音草稿文件夹")
        val file = File(draftsDirectory, "${UUID.randomUUID()}.${format.extension}")
        if (!file.createNewFile()) throw IOException("无法创建录音草稿")
        return RecordingDraft(file, format)
    }

    fun recoverDrafts(): List<RecordingDraft> {
        if (!draftsDirectory.exists()) return emptyList()
        val files = draftsDirectory.listFiles() ?: throw IOException("无法读取录音草稿")
        // 仅枚举，不修复或删除文件；服务可能仍持有正在写入的草稿。
        return files.filter { it.isFile && it.length() > 0 }.sortedByDescending { it.lastModified() }.mapNotNull { file ->
            RecordingFormat.entries.firstOrNull { it.extension.equals(file.extension, true) }?.let { RecordingDraft(file, it) }
        }
    }

    fun publish(draft: RecordingDraft, name: String, durationMillis: Long): RecordingEntry {
        require(draft.file.canonicalFile.parentFile == draftsDirectory.canonicalFile && draft.file.isFile) { "无效的录音草稿" }
        require(draft.file.extension.equals(draft.format.extension, true)) { "录音草稿格式不匹配" }
        require(draft.file.length() > 0 && durationMillis >= 0) { "录音草稿为空或时长无效" }
        val displayName = validatedName(name, draft.format.extension)
        // 发布成功后也保留源文件，由服务在 clearDraft 时删除，避免失败丢失录音。
        return treeUri?.let { publishDocument(it, draft, displayName, durationMillis) }
            ?: if (Build.VERSION.SDK_INT >= 29) publishScoped(draft, displayName, durationMillis)
            else publishLegacy(draft, displayName, durationMillis)
    }

    fun list(): List<RecordingEntry> {
        val entries = mutableListOf<RecordingEntry>()
        if (Build.VERSION.SDK_INT >= 29 || canReadAudio()) {
            for (collection in collections()) {
                val pathColumn = if (Build.VERSION.SDK_INT >= 29) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA
                val args = mutableListOf<String>()
                val folderSelection = RECORDING_FOLDERS.joinToString(" OR ") { folder ->
                    if (Build.VERSION.SDK_INT >= 29) {
                        args += "$folder/%"
                        args += "%/$folder/%"
                        "($pathColumn LIKE ? OR $pathColumn LIKE ?)"
                    } else {
                        args += "%/$folder/%"
                        "$pathColumn LIKE ?"
                    }
                }
                var selection = "($folderSelection)"
                if (Build.VERSION.SDK_INT >= 29) {
                    selection += " AND ${MediaStore.MediaColumns.IS_PENDING} = ?"
                    args += "0"
                    if (!canReadAudio()) {
                        selection += " AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?"
                        args += context.packageName
                    }
                }
                query(collection, mediaProjection(), selection, args.toTypedArray()).use { cursor ->
                    while (cursor.moveToNext()) entries += mediaEntry(cursor, ContentUris.withAppendedId(collection, cursor.long(MediaStore.MediaColumns._ID)))
                }
            }
        }
        treeUri?.let { tree ->
            query(childrenUri(tree), DOCUMENT_PROJECTION).use { cursor ->
                while (cursor.moveToNext()) {
                    val mime = cursor.text(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val name = cursor.text(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    if (mime != DocumentsContract.Document.MIME_TYPE_DIR &&
                        (mime.startsWith("audio/") || RecordingFormat.entries.any { name.endsWith(".${it.extension}", true) })) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.text(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
                        entries += documentEntry(cursor, uri, audioDuration(uri), tree.toString())
                    }
                }
            }
        }
        return entries.distinctBy { it.uri }.sortedByDescending { it.modifiedSeconds }
    }

    fun rename(entry: RecordingEntry, name: String): RecordingEntry {
        val extension = entry.name.substringAfterLast('.', "").ifEmpty {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(entry.mimeType).orEmpty()
        }
        val displayName = validatedName(name, extension)
        if (displayName == entry.name) return entry
        if (DocumentsContract.isDocumentUri(context, entry.uri)) {
            requireDocumentNameAvailable(entry.uri, displayName, entry.uri)
            val renamed = DocumentsContract.renameDocument(resolver, entry.uri, displayName)
                ?: throw IOException("文件提供程序未能重命名录音")
            return readDocument(renamed, entry.durationMillis, entry.folder)
        }
        val current = readMedia(entry.uri)
        requireMediaNameAvailable(mediaCollection(entry.uri), current.folder, displayName, entry.uri)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.TITLE, displayName.substringBeforeLast('.', displayName))
        }
        if (Build.VERSION.SDK_INT >= 29) {
            // SecurityException 原样传给界面，允许用户授权修改其他应用的录音。
            if (resolver.update(entry.uri, values, null, null) != 1) throw IOException("无法重命名录音")
            return readMedia(entry.uri)
        }
        requireLegacyWrite()
        val source = File(mediaPath(entry.uri))
        val destination = File(source.parentFile, displayName)
        Files.move(source.toPath(), destination.toPath())
        try {
            values.put(MediaStore.MediaColumns.DATA, destination.absolutePath)
            if (resolver.update(entry.uri, values, null, null) != 1) throw IOException("无法更新录音索引")
        } catch (failure: Exception) {
            cleanup(failure) { Files.move(destination.toPath(), source.toPath()) }
            throw failure
        }
        return readMedia(entry.uri)
    }

    fun delete(entry: RecordingEntry) {
        if (DocumentsContract.isDocumentUri(context, entry.uri)) {
            if (!DocumentsContract.deleteDocument(resolver, entry.uri)) throw IOException("文件提供程序未能删除录音")
        } else {
            val legacyFile = if (Build.VERSION.SDK_INT < 29) {
                requireLegacyWrite()
                File(mediaPath(entry.uri))
            } else null
            if (resolver.delete(entry.uri, null, null) != 1) throw IOException("录音不存在或无法删除")
            if (legacyFile != null && legacyFile.exists() && !legacyFile.delete()) throw IOException("媒体索引已删除，但录音文件无法删除")
        }
    }

    private fun publishScoped(draft: RecordingDraft, name: String, duration: Long): RecordingEntry {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        requireMediaNameAvailable(collection, PUBLIC_FOLDER, name)
        val values = audioValues(draft, name, duration).apply {
            put(MediaStore.MediaColumns.RELATIVE_PATH, PUBLIC_FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("无法创建录音媒体条目")
        try {
            val expected = resolver.openOutputStream(uri, "w")?.use { copyVerifiedSource(draft.file, it) }
                ?: throw IOException("无法写入录音")
            verifyCopy(uri, expected)
            val finished = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                put(MediaStore.Audio.Media.DURATION, duration)
            }
            if (resolver.update(uri, finished, null, null) != 1) throw IOException("无法发布录音")
            val saved = readMedia(uri).copy(durationMillis = duration)
            if (saved.name != name) throw IOException("媒体存储更改了录音名称，草稿已保留")
            return saved
        } catch (failure: Exception) {
            cleanup(failure) { if (resolver.delete(uri, null, null) != 1) throw IOException("无法清理未完成的录音") }
            throw failure
        }
    }

    private fun publishLegacy(draft: RecordingDraft, name: String, duration: Long): RecordingEntry {
        requireLegacyWrite()
        val directory = File(Environment.getExternalStorageDirectory(), "SoundRecorder")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建录音文件夹")
        val file = File(directory, name)
        requireMediaNameAvailable(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, directory.absolutePath, name)
        if (!file.createNewFile()) throw IOException("同名录音已存在")
        var inserted: Uri? = null
        try {
            val expected = FileOutputStream(file).use { output ->
                val hash = copyVerifiedSource(draft.file, output)
                output.fd.sync()
                hash
            }
            file.inputStream().use { verifyDigest(it, expected) }
            val values = audioValues(draft, name, duration).apply {
                put(MediaStore.MediaColumns.DATA, file.absolutePath)
                put(MediaStore.MediaColumns.SIZE, file.length())
            }
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("无法建立录音媒体索引")
            inserted = uri
            return readMedia(uri).copy(durationMillis = duration)
        } catch (failure: Exception) {
            inserted?.let { uri -> cleanup(failure) { if (resolver.delete(uri, null, null) != 1) throw IOException("无法清理录音索引") } }
            cleanup(failure) { if (file.exists() && !file.delete()) throw IOException("无法清理未完成的录音文件") }
            throw failure
        }
    }

    private fun publishDocument(tree: Uri, draft: RecordingDraft, name: String, duration: Long): RecordingEntry {
        requireDocumentNameAvailable(tree, name)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val uri = DocumentsContract.createDocument(resolver, parent, draft.format.mimeType, name)
            ?: throw IOException("无法在所选文件夹创建录音")
        try {
            if (readDocument(uri, duration, tree.toString()).name != name) throw IOException("同名录音已存在或文件提供程序更改了名称")
            val expected = resolver.openOutputStream(uri, "w")?.use { copyVerifiedSource(draft.file, it) }
                ?: throw IOException("无法写入所选文件夹")
            verifyCopy(uri, expected)
            return readDocument(uri, duration, tree.toString())
        } catch (failure: Exception) {
            cleanup(failure) { if (!DocumentsContract.deleteDocument(resolver, uri)) throw IOException("无法清理未完成的录音") }
            throw failure
        }
    }

    private fun audioValues(draft: RecordingDraft, name: String, duration: Long) = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.Audio.Media.TITLE, name.substringBeforeLast('.', name))
        put(MediaStore.MediaColumns.MIME_TYPE, draft.format.mimeType)
        put(MediaStore.Audio.Media.DURATION, duration)
        put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000)
        put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000)
        put(MediaStore.Audio.Media.IS_MUSIC, 0)
    }

    private fun validatedName(name: String, extension: String): String {
        val base = name.trim()
        require(base.isNotEmpty() && base != "." && base != "..") { "录音名称不能为空" }
        require(name.none { it == '/' || it == '\\' || Character.isISOControl(it) } &&
            extension.none { it == '/' || it == '\\' || Character.isISOControl(it) }) { "录音名称不能包含路径分隔符或控制字符" }
        val result = if (extension.isEmpty() || base.endsWith(".$extension", true)) base else "$base.$extension"
        require(result.toByteArray(Charsets.UTF_8).size <= 255) { "录音名称过长（最多 255 个 UTF-8 字节）" }
        return result
    }

    private fun requireMediaNameAvailable(collection: Uri, folder: String, name: String, except: Uri? = null) {
        val path = if (Build.VERSION.SDK_INT >= 29) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA
        val location = if (Build.VERSION.SDK_INT >= 29) folder else File(folder, name).absolutePath
        val selection = if (Build.VERSION.SDK_INT >= 29) "$path = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ? COLLATE NOCASE" else "$path = ? COLLATE NOCASE"
        val args = if (Build.VERSION.SDK_INT >= 29) arrayOf(location, name) else arrayOf(location)
        query(collection, arrayOf(MediaStore.MediaColumns._ID), selection, args).use { cursor ->
            while (cursor.moveToNext()) {
                if (ContentUris.withAppendedId(collection, cursor.getLong(0)) != except) throw IOException("同名录音已存在")
            }
        }
    }

    private fun requireDocumentNameAvailable(tree: Uri, name: String, except: Uri? = null) {
        query(childrenUri(tree), arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)).use { cursor ->
            while (cursor.moveToNext()) {
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                if (cursor.getString(1).equals(name, true) && uri != except) throw IOException("同名录音已存在")
            }
        }
    }

    private fun childrenUri(tree: Uri): Uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    private fun readMedia(uri: Uri): RecordingEntry = query(uri, mediaProjection()).use { cursor ->
        if (!cursor.moveToFirst()) throw IOException("录音媒体条目不存在")
        mediaEntry(cursor, uri)
    }

    private fun mediaEntry(cursor: Cursor, uri: Uri): RecordingEntry {
        val folder = if (Build.VERSION.SDK_INT >= 29) cursor.text(MediaStore.MediaColumns.RELATIVE_PATH)
            else File(cursor.text(MediaStore.MediaColumns.DATA)).parent.orEmpty()
        // 媒体库扫描可能尚未补全时长；仅未知条目回读实际容器，避免整库逐个解码。
        val duration = cursor.long(MediaStore.Audio.Media.DURATION).takeIf { it > 0 } ?: audioDuration(uri)
        return RecordingEntry(uri, cursor.text(MediaStore.MediaColumns.DISPLAY_NAME), cursor.text(MediaStore.MediaColumns.MIME_TYPE),
            duration, cursor.long(MediaStore.MediaColumns.DATE_MODIFIED), cursor.long(MediaStore.MediaColumns.SIZE), folder)
    }

    private fun mediaProjection() = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.MIME_TYPE, MediaStore.Audio.Media.DURATION, MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.SIZE, if (Build.VERSION.SDK_INT >= 29) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA)

    private fun mediaPath(uri: Uri): String = query(uri, arrayOf(MediaStore.MediaColumns.DATA)).use { cursor ->
        if (!cursor.moveToFirst()) throw IOException("录音文件不存在")
        cursor.getString(0) ?: throw IOException("录音文件路径不存在")
    }

    private fun mediaCollection(uri: Uri): Uri {
        require(uri.authority == MediaStore.AUTHORITY && uri.pathSegments.size >= 4 && uri.pathSegments[1] == "audio") { "无效的录音媒体 URI" }
        return MediaStore.Audio.Media.getContentUri(uri.pathSegments[0])
    }

    private fun collections(): List<Uri> = if (Build.VERSION.SDK_INT >= 29) {
        MediaStore.getExternalVolumeNames(context).sorted().map(MediaStore.Audio.Media::getContentUri)
    } else listOf(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)

    private fun readDocument(uri: Uri, duration: Long, folder: String): RecordingEntry = query(uri, DOCUMENT_PROJECTION).use { cursor ->
        if (!cursor.moveToFirst()) throw IOException("录音文档不存在")
        documentEntry(cursor, uri, duration, folder)
    }

    private fun documentEntry(cursor: Cursor, uri: Uri, duration: Long, folder: String) = RecordingEntry(uri,
        cursor.text(DocumentsContract.Document.COLUMN_DISPLAY_NAME), cursor.text(DocumentsContract.Document.COLUMN_MIME_TYPE),
        duration, cursor.long(DocumentsContract.Document.COLUMN_LAST_MODIFIED) / 1000, cursor.long(DocumentsContract.Document.COLUMN_SIZE), folder)

    private fun audioDuration(uri: Uri): Long {
        val metadata = MediaMetadataRetriever()
        return try {
            metadata.setDataSource(context, uri)
            metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        } catch (_: IllegalArgumentException) {
            // 部分文档提供程序不支持媒体元数据；时长未知不影响文件可见性。
            0
        } catch (_: RuntimeException) {
            0
        } finally {
            metadata.release()
        }
    }

    private fun canReadAudio(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT < 29 && context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
    }

    private fun requireLegacyWrite() {
        if (context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("保存或修改录音需要存储写入权限")
        }
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) throw IOException("外部存储不可写")
    }

    private fun query(uri: Uri, projection: Array<String>, selection: String? = null, args: Array<String>? = null): Cursor =
        resolver.query(uri, projection, selection, args, null) ?: throw IOException("无法读取录音存储：$uri")

    private fun Cursor.text(column: String): String = getString(getColumnIndexOrThrow(column)).orEmpty()
    private fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))

    private fun copyVerifiedSource(source: File, output: OutputStream): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256")
        val expectedSize = source.length()
        // 复制时顺便计算摘要；不重复读取原文件，长录音仍只用固定缓冲区。
        DigestInputStream(source.inputStream(), hash).use { input ->
            if (input.copyTo(output) != expectedSize) throw IOException("录音草稿在保存时发生变化")
        }
        output.flush()
        return hash.digest()
    }

    private fun verifyCopy(destination: Uri, expected: ByteArray) {
        val input = resolver.openInputStream(destination) ?: throw IOException("无法校验已保存录音")
        input.use { verifyDigest(it, expected) }
    }

    private fun verifyDigest(input: InputStream, expected: ByteArray) {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            hash.update(buffer, 0, count)
        }
        if (!hash.digest().contentEquals(expected)) throw IOException("录音校验失败，草稿已保留")
    }

    private fun cleanup(failure: Exception, action: () -> Unit) {
        try { action() } catch (cleanupFailure: Exception) { failure.addSuppressed(cleanupFailure) }
    }

    companion object {
        private const val PUBLIC_FOLDER = "Music/SoundRecorder/"
        private val RECORDING_FOLDERS = arrayOf("SoundRecorder", "Recording", "FMRecording", "CallRecord")
        private val DOCUMENT_PROJECTION = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
    }
}
