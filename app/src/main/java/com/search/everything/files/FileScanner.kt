package com.search.everything.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 扫描进度回调：(已扫描条目数, 当前目录名) */
typealias ScanProgress = (count: Int, currentDir: String) -> Unit

class FileScanner(private val context: Context) {

    private val dao = FileDatabase.get(context).fileDao()

    /** 这些目录没多大搜索价值，跳过以提速 */
    private val skipDirs = setOf(
        ".thumbnails", ".trash", "lost+found", ".recycle"
    )

    /**
     * 全量遍历 /sdcard，批量入库。结束后清掉本次没扫到的旧条目（即已删除的文件）。
     * 返回索引条目总数。
     */
    suspend fun fullScan(onProgress: ScanProgress): Int = withContext(Dispatchers.IO) {
        val session = System.currentTimeMillis()
        val root = Environment.getExternalStorageDirectory()
        val batch = ArrayList<FileEntry>(500)
        var count = 0

        suspend fun flush() {
            if (batch.isNotEmpty()) {
                dao.upsertAll(batch.toList())
                batch.clear()
            }
        }

        val stack = ArrayDeque<File>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (f in children) {
                if (f.isDirectory && f.name in skipDirs) continue
                batch.add(
                    FileEntry(
                        path = f.absolutePath,
                        name = f.name,
                        isDir = f.isDirectory,
                        size = if (f.isDirectory) 0L else f.length(),
                        mtime = f.lastModified(),
                        scanSession = session,
                    )
                )
                count++
                if (f.isDirectory) stack.add(f)
                if (batch.size >= 500) {
                    flush()
                    onProgress(count, f.parent ?: "")
                }
            }
        }
        flush()
        dao.deleteStaleSessions(session)
        dao.count()
    }

    suspend fun count(): Int = dao.count()

    suspend fun search(query: String): List<FileEntry> = withContext(Dispatchers.IO) {
        dao.search(query.trim())
    }

    fun openFile(entry: FileEntry) {
        val file = File(entry.path)
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val mime = if (entry.isDir) {
            "resource/folder"
        } else {
            MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            toast("没有能打开此类型文件的应用")
        }
    }

    fun shareFile(entry: FileEntry) {
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", File(entry.path)
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(
                Intent.createChooser(intent, "分享").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            toast("分享失败")
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        fun formatSize(size: Long): String = when {
            size >= 1L shl 30 -> "%.1f GB".format(size / (1024.0 * 1024 * 1024))
            size >= 1L shl 20 -> "%.1f MB".format(size / (1024.0 * 1024))
            size >= 1L shl 10 -> "%.1f KB".format(size / 1024.0)
            else -> "$size B"
        }
    }
}
