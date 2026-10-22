package com.search.everything.a11y

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍服务本体：只用来读取 MIUI 桌面（com.miui.home）的图标布局。
 * 实例通过 companion 暴露给 DesktopScanner；onAccessibilityEvent 只打心跳，
 * 扫描全程靠轮询 rootInActiveWindow（比事件状态机更耐 MIUI 的事件风暴）。
 */
class DesktopA11yService : AccessibilityService() {

    @Volatile
    var lastEventTime: Long = 0L
        private set

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        lastEventTime = System.currentTimeMillis()
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        android.util.Log.d("DesktopScanner", "a11y service connected")
        instance = this
    }

    override fun onDestroy() {
        android.util.Log.d("DesktopScanner", "a11y service destroyed")
        instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: DesktopA11yService? = null
            private set

        /** 无障碍服务是否已被用户（或 adb）开启 */
        fun isEnabled(): Boolean = instance != null
    }
}
