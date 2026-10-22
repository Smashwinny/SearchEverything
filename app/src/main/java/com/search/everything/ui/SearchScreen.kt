package com.search.everything.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.search.everything.apps.AppInfo
import com.search.everything.apps.AppLocation
import com.search.everything.files.FileEntry
import com.search.everything.files.FileScanner

/** 位置的人类可读描述，如 "第2屏 · 文件夹「实用工具」第5个" / "第1屏 第3行第2列" / "Dock 栏" */
private fun AppLocation.describe(): String {
    val base = if (isDock) "Dock 栏" else "第${page}屏"
    return when {
        isFolder -> "$base · 这是个文件夹"
        folderName != null -> "$base · 文件夹「$folderName」第${indexInFolder}个"
        row != null && col != null -> "$base · 第${row}行第${col}列"
        else -> base
    }
}

@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onRequestAllFilesAccess: () -> Unit,
    onOpenA11ySettings: () -> Unit,
) {
    val query by viewModel.query.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val results by viewModel.results.collectAsState()
    val storageState by viewModel.storageState.collectAsState()
    val desktopState by viewModel.desktopState.collectAsState()
    val locations by viewModel.locations.collectAsState()
    val lastDesktopScan by viewModel.lastDesktopScan.collectAsState()
    val lastFileScan by viewModel.lastFileScan.collectAsState()

    var fileActionTarget by remember { mutableStateOf<FileEntry?>(null) }

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
        ) {
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索应用或文件…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "清空")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
            )

            Row(
                Modifier.padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = filter == Filter.ALL,
                    onClick = { viewModel.setFilter(Filter.ALL) },
                    label = { Text("全部") })
                FilterChip(
                    selected = filter == Filter.APPS,
                    onClick = { viewModel.setFilter(Filter.APPS) },
                    label = { Text("应用") })
                FilterChip(
                    selected = filter == Filter.FILES,
                    onClick = { viewModel.setFilter(Filter.FILES) },
                    label = { Text("文件") })
                Spacer(Modifier.weight(1f))
                if (storageState is StorageState.Ready) {
                    TextButton(onClick = { viewModel.startScanIfNeeded(force = true) }) {
                        Text("重建索引")
                    }
                }
            }

            // 文件索引状态行：授权引导 / 扫描进度 / 上次扫描时间
            when (val s = storageState) {
                StorageState.NotGranted -> {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "授权\"所有文件访问\"后可搜索全盘文件",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = onRequestAllFilesAccess) { Text("去授权") }
                    }
                }
                is StorageState.Scanning -> {
                    Column(Modifier.padding(bottom = 8.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            "正在建立文件索引… 已扫描 ${s.count} 项",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                is StorageState.Ready -> {
                    Text(
                        "文件索引：${s.total} 个文件" +
                            (if (lastFileScan > 0) " · 上次更新 ${formatTime(lastFileScan)}" else "") +
                            "（新增文件点右上角\"重建索引\"刷新）",
                        Modifier.padding(bottom = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            when (val d = desktopState) {
                DesktopState.A11yOff -> {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "开启无障碍服务后可索引应用在桌面的位置",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = onOpenA11ySettings) { Text("去开启") }
                    }
                }
                DesktopState.Idle -> {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (locations.isEmpty()) "还没有桌面位置索引"
                            else "桌面索引：${locations.size} 个图标" +
                                (if (lastDesktopScan > 0) " · 上次扫描 ${formatTime(lastDesktopScan)}" else "") +
                                "\n（只有移动过图标/文件夹才需要重新扫描，不用每次扫）",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { viewModel.startDesktopScan() }) {
                            Text("扫描桌面位置")
                        }
                    }
                }
                is DesktopState.Scanning -> {
                    Column(Modifier.padding(bottom = 8.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            "${d.progress}（请勿触碰屏幕）",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                is DesktopState.Done -> {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "桌面索引完成：${d.count} 个图标" +
                                (if (lastDesktopScan > 0) " · ${formatTime(lastDesktopScan)}" else ""),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { viewModel.startDesktopScan() }) { Text("重新扫描") }
                    }
                }
                is DesktopState.Error -> {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "扫描失败：${d.reason}",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = { viewModel.startDesktopScan() }) { Text("重试") }
                    }
                }
            }

            LazyColumn(Modifier.fillMaxSize()) {
                if (results.apps.isNotEmpty() && filter != Filter.FILES) {
                    item { SectionHeader("应用 (${results.apps.size})") }
                    items(results.apps, key = { "app:" + it.packageName }) { app ->
                        AppRow(
                            app = app,
                            location = locations[app.label],
                            onClick = { viewModel.launchApp(app.packageName) },
                            onLongClick = { viewModel.openAppDetails(app.packageName) },
                        )
                    }
                }
                if (results.files.isNotEmpty() && filter != Filter.APPS) {
                    item { SectionHeader("文件 (${results.files.size})") }
                    items(results.files, key = { "file:" + it.path }) { entry ->
                        FileRow(
                            entry = entry,
                            onClick = { viewModel.openFile(entry) },
                            onLongClick = { fileActionTarget = entry },
                        )
                    }
                }
                if (query.isNotBlank() && results.apps.isEmpty() && results.files.isEmpty()) {
                    item {
                        Text(
                            "没有匹配结果",
                            Modifier.padding(24.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }

    fileActionTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { fileActionTarget = null },
            title = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = { Text(entry.path, style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.shareFile(entry); fileActionTarget = null
                }) { Text("分享") }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.openFile(
                        entry.copy(
                            path = entry.path.substringBeforeLast('/'),
                            isDir = true
                        )
                    ); fileActionTarget = null
                }) { Text("打开所在目录") }
            },
        )
    }
}

private fun formatTime(epochMs: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(epochMs))

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        Modifier.padding(top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
    HorizontalDivider()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppRow(
    app: AppInfo,
    location: AppLocation?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val icon = remember(app.packageName) { app.icon.toBitmap().asImageBitmap() }
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            bitmap = icon,
            contentDescription = null,
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (location != null) {
                Text(
                    location.describe(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(entry: FileEntry, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (entry.isDir) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
            modifier = Modifier.size(36.dp),
            tint = if (entry.isDir) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            val sub = if (entry.isDir) entry.path
            else "${FileScanner.formatSize(entry.size)}  ·  ${entry.path}"
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
