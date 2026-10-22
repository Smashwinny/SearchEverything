package com.search.everything

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.search.everything.ui.SearchScreen
import com.search.everything.ui.SearchViewModel

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: SearchViewModel

    private val readStorageLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startScanIfNeeded()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[SearchViewModel::class.java]
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                SearchScreen(
                    viewModel = viewModel,
                    onRequestAllFilesAccess = ::requestStorageAccess,
                    onOpenA11ySettings = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
        // adb 调试入口：am start --ez autoscan true 直接触发桌面扫描，避免盲点按钮
        if (intent?.getBooleanExtra("autoscan", false) == true) {
            intent?.removeExtra("autoscan")
            window.decorView.postDelayed({ viewModel.startDesktopScan() }, 1500)
        }
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 跳系统"所有文件访问"设置页
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } else {
            when (ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_EXTERNAL_STORAGE
            )) {
                PackageManager.PERMISSION_GRANTED -> viewModel.startScanIfNeeded()
                else -> readStorageLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }
}
