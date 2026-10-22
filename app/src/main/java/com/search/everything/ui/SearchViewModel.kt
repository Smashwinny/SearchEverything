package com.search.everything.ui

import android.app.Application
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.search.everything.a11y.DesktopA11yService
import com.search.everything.a11y.DesktopScanner
import com.search.everything.apps.AppInfo
import com.search.everything.apps.AppLocation
import com.search.everything.apps.AppRepository
import com.search.everything.files.FileDatabase
import com.search.everything.files.FileEntry
import com.search.everything.files.FileScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

enum class Filter { ALL, APPS, FILES }

sealed interface StorageState {
    data object NotGranted : StorageState
    data class Scanning(val count: Int, val currentDir: String) : StorageState
    data class Ready(val total: Int) : StorageState
}

sealed interface DesktopState {
    data object A11yOff : DesktopState
    data object Idle : DesktopState
    data class Scanning(val progress: String) : DesktopState
    data class Done(val count: Int) : DesktopState
    data class Error(val reason: String) : DesktopState
}

data class SearchResults(
    val apps: List<AppInfo> = emptyList(),
    val files: List<FileEntry> = emptyList(),
)

@OptIn(FlowPreview::class)
class SearchViewModel(app: Application) : AndroidViewModel(app) {

    private val appRepo = AppRepository(app)
    private val fileScanner = FileScanner(app)
    private val locationDao = FileDatabase.get(app).appLocationDao()

    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()

    private val _filter = MutableStateFlow(Filter.ALL)
    val filter = _filter.asStateFlow()

    private val _results = MutableStateFlow(SearchResults())
    val results = _results.asStateFlow()

    private val _storageState = MutableStateFlow<StorageState>(StorageState.NotGranted)
    val storageState = _storageState.asStateFlow()

    private val _desktopState = MutableStateFlow<DesktopState>(DesktopState.Idle)
    val desktopState = _desktopState.asStateFlow()

    /** 上次桌面扫描时间（epoch ms，0=从未） */
    private val prefs = app.getSharedPreferences("scan_state", android.content.Context.MODE_PRIVATE)
    private val _lastDesktopScan = MutableStateFlow(prefs.getLong("last_desktop_scan", 0L))
    val lastDesktopScan = _lastDesktopScan.asStateFlow()
    private val _lastFileScan = MutableStateFlow(prefs.getLong("last_file_scan", 0L))
    val lastFileScan = _lastFileScan.asStateFlow()

    /** 图标名 -> 桌面位置（上次扫描结果） */
    private val _locations = MutableStateFlow<Map<String, AppLocation>>(emptyMap())
    val locations = _locations.asStateFlow()

    private var allApps: List<AppInfo> = emptyList()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            allApps = appRepo.loadApps()
            _locations.value = locationDao.all().associateBy { it.label }
        }
        viewModelScope.launch {
            _query.debounce(150).collectLatest { q ->
                _results.value = SearchResults(
                    apps = if (_filter.value != Filter.FILES) searchApps(q) else emptyList(),
                    files = if (_filter.value != Filter.APPS &&
                        _storageState.value is StorageState.Ready && q.isNotBlank()
                    ) fileScanner.search(q) else emptyList(),
                )
            }
        }
    }

    fun onQueryChange(q: String) {
        _query.value = q
    }

    fun setFilter(f: Filter) {
        _filter.value = f
        _query.value = _query.value // 触发重新搜索
    }

    private fun searchApps(q: String): List<AppInfo> {
        if (q.isBlank()) return allApps
        return allApps.mapNotNull { app ->
            appRepo.matchScore(app, q)?.let { score -> score to app }
        }.sortedBy { it.first }.map { it.second }
    }

    /** 每次 onResume 调用：刷新权限状态和已装应用 */
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            allApps = appRepo.loadApps()
            if (_query.value.isNotBlank()) {
                _results.value = _results.value.copy(apps = searchApps(_query.value))
            } else if (_filter.value != Filter.FILES) {
                _results.value = _results.value.copy(apps = allApps)
            }
        }
        checkStoragePermission()
        refreshA11yState()
    }

    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            androidx.core.content.ContextCompat.checkSelfPermission(
                getApplication(), android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    fun checkStoragePermission() {
        val granted = hasAllFilesAccess()
        val current = _storageState.value
        if (granted && current is StorageState.NotGranted) {
            startScanIfNeeded()
        } else if (!granted) {
            _storageState.value = StorageState.NotGranted
        }
    }

    /** 已授权后开始（或重新开始）全量扫描 */
    fun startScanIfNeeded(force: Boolean = false) {
        if (!hasAllFilesAccess()) return
        if (_storageState.value is StorageState.Scanning) return
        if (!force && _storageState.value is StorageState.Ready) return
        viewModelScope.launch(Dispatchers.IO) {
            _storageState.value = StorageState.Scanning(0, "")
            val total = fileScanner.fullScan { count, dir ->
                _storageState.value = StorageState.Scanning(count, dir)
            }
            _storageState.value = StorageState.Ready(total)
            System.currentTimeMillis().let { now ->
                prefs.edit().putLong("last_file_scan", now).apply()
                _lastFileScan.value = now
            }
        }
    }

    // ---- 桌面位置索引 ----

    fun isA11yEnabled(): Boolean {
        if (DesktopA11yService.instance != null) return true
        val enabled = Settings.Secure.getString(
            getApplication<Application>().contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(
            "com.search.everything/com.search.everything.a11y.DesktopA11yService"
        )
    }

    fun refreshA11yState() {
        if (!isA11yEnabled()) _desktopState.value = DesktopState.A11yOff
        else if (_desktopState.value == DesktopState.A11yOff) _desktopState.value = DesktopState.Idle
    }

    fun startDesktopScan() {
        val service = DesktopA11yService.instance
        android.util.Log.d("DesktopScanner", "startDesktopScan: service=$service state=${_desktopState.value}")
        if (service == null) {
            _desktopState.value = DesktopState.A11yOff
            return
        }
        if (_desktopState.value is DesktopState.Scanning) return
        viewModelScope.launch(Dispatchers.Default) {
            if (allApps.isEmpty()) allApps = appRepo.loadApps() // 裸模式图标识别依赖它
            val result = DesktopScanner(
                service,
                knownLabels = allApps.map { it.label }.toSet(),
            ).scan { progress ->
                _desktopState.value = DesktopState.Scanning(progress)
            }
            when (result) {
                is DesktopScanner.ScanResult.Ok -> {
                    locationDao.clear()
                    locationDao.upsertAll(result.locations)
                    _locations.value = result.locations.associateBy { it.label }
                    _desktopState.value = DesktopState.Done(result.locations.size)
                    System.currentTimeMillis().let { now ->
                        prefs.edit().putLong("last_desktop_scan", now).apply()
                        _lastDesktopScan.value = now
                    }
                }
                is DesktopScanner.ScanResult.Fail ->
                    _desktopState.value = DesktopState.Error(result.reason)
            }
        }
    }

    fun launchApp(packageName: String) = appRepo.launch(packageName)
    fun openAppDetails(packageName: String) = appRepo.openAppDetails(packageName)
    fun openFile(entry: FileEntry) = fileScanner.openFile(entry)
    fun shareFile(entry: FileEntry) = fileScanner.shareFile(entry)
}
