package com.search.everything.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.search.everything.apps.AppLocation
import kotlinx.coroutines.delay
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * MIUI 桌面布局扫描器（实测 HyperOS，包名 com.miui.home）：
 * - 页码：SeekBar 的 content-desc = "第 X 页共 N 页"
 * - 桌面项：clickable 的 RelativeLayout（content-desc = 名称），
 *   含后代 preview_icons_container 的是文件夹
 * - 打开文件夹后：id/title = 文件夹名，id/folder_grid_view 内 TextView desc = 应用名
 */
class DesktopScanner(
    private val service: AccessibilityService,
    /** 已安装应用的桌面名称集合，用于识别"裸 TextView"形态的桌面图标 */
    private val knownLabels: Set<String> = emptySet(),
) {

    sealed interface ScanResult {
        data class Ok(val locations: List<AppLocation>) : ScanResult
        data class Fail(val reason: String) : ScanResult
    }

    companion object {
        private const val TAG = "DesktopScanner"
        private const val HOME_PKG = "com.miui.home"
        private val PAGE_REGEX = Regex("第\\s*(\\d+)\\s*页共\\s*(\\d+)\\s*页")
        private val BADGE_REGEX = Regex("：\\d+条消息未读$")

        /** 全局并发闸：两个 Activity/ViewModel 实例也不允许同时扫描 */
        val scanning = java.util.concurrent.atomic.AtomicBoolean(false)

        /** 去掉图标本描述里的未读角标尾巴，如 "抖音：1条消息未读" -> "抖音" */
        fun stripBadge(label: String): String = label.replace(BADGE_REGEX, "")
    }

    suspend fun scan(onProgress: (String) -> Unit): ScanResult {
        if (!scanning.compareAndSet(false, true)) {
            return ScanResult.Fail("已有扫描在进行中")
        }
        try {
            return doScan(onProgress)
        } finally {
            scanning.set(false)
        }
    }

    private suspend fun doScan(onProgress: (String) -> Unit): ScanResult {
        // 先回桌面
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        if (awaitHome() == null) {
            Log.w(TAG, "fail: cannot reach home")
            return ScanResult.Fail("无法回到桌面，请解锁手机后重试")
        }
        // 桌面上若残留打开的文件夹（页码指示器会被遮挡），按返回键关掉
        // 注意：必须在回到桌面之后再按，否则会误关我们自己的 Activity
        val root0 = service.rootInActiveWindow
        if (root0?.packageName == HOME_PKG &&
            !root0.findAccessibilityNodeInfosByViewId("$HOME_PKG:id/folder_grid_view").isNullOrEmpty()
        ) {
            Log.d(TAG, "closing leftover open folder")
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            delay(500)
        }
        delay(800) // 等桌面动画和页码指示器稳定

        // 总页数只信页码指示器；当前页用内部计数器跟踪（desc 有更新延迟，实测会读到陈旧值）
        val totalPages = currentPage()?.second
        if (totalPages == null) {
            Log.w(TAG, "fail: no page indicator, root pkg=${service.rootInActiveWindow?.packageName}")
            return ScanResult.Fail("读不到桌面页码，请确认使用的是系统桌面")
        }
        Log.d(TAG, "total pages = $totalPages")

        // 先翻到第 1 屏：连续右滑直到指示器确认到第 1 屏
        var page = currentPage()?.first ?: 1
        var guard = 0
        while (page != 1 && guard++ < 10) {
            onProgress("正在翻到第 1 屏…")
            Log.d(TAG, "nav-to-first: indicator says page=$page, swiping right")
            swipe(forward = false)
            delay(700)
            page = currentPage()?.first ?: page - 1
        }
        page = 1 // 右滑到头后就在第 1 屏，此后靠自己计数

        val locations = mutableListOf<AppLocation>()
        while (true) {
            onProgress("正在扫描第 $page / $totalPages 屏…")
            Log.d(TAG, "scanPage page=$page (indicator=${currentPage()})")

            scanPage(page, locations, onProgress)
                ?: return ScanResult.Fail("第 $page 屏扫描中断（屏幕被切走？）")

            if (page >= totalPages) break
            swipe(forward = true)
            delay(900) // 等翻页动画结束
            Log.d(TAG, "after swipe, indicator=${currentPage()}")
            page++
        }
        return ScanResult.Ok(locations)
    }

    /** 扫描当前屏，逐个处理桌面项；中断返回 null */
    private suspend fun scanPage(
        page: Int,
        out: MutableList<AppLocation>,
        onProgress: (String) -> Unit,
    ): Unit? {
        val root = service.rootInActiveWindow ?: return null
        if (root.packageName != HOME_PKG) return null

        val items = collectItems(root, page)
        Log.d(TAG, "page $page: ${items.size} items " +
            "(${items.count { it.isFolder }} folders, ${items.count { it.isDock }} dock)")

        for (item in items) {
            if (item.isFolder) {
                onProgress("第 $page 屏：打开文件夹「${item.label}」")
                // 同名文件夹主键冲突时加位置后缀
                var folderEntryLabel = item.label
                if (out.any { it.label == folderEntryLabel && it.isFolder }) {
                    folderEntryLabel = "${item.label}（第${page}屏 ${item.row}行${item.col}列）"
                }
                out.add(
                    AppLocation(
                        label = folderEntryLabel, page = page, folderName = null,
                        row = item.row, col = item.col, isDock = false, isFolder = true,
                    )
                )
                if (!scanFolder(item.node, item.label, page, out)) return null
            } else {
                out.add(
                    AppLocation(
                        label = item.label, page = page, folderName = null,
                        row = item.row, col = item.col, isDock = item.isDock,
                    )
                )
            }
        }
        return Unit
    }

    private class DesktopItem(
        val node: AccessibilityNodeInfo,
        val label: String,
        val rect: Rect,
        val isDock: Boolean,
        val isFolder: Boolean,
        var row: Int? = null,
        var col: Int? = null,
    )

    /**
     * 枚举当前屏的桌面项。MIUI 有两种图标形态：
     * 富模式（RelativeLayout + icon_container/preview_icons_container id）
     * 和裸模式（无 id 的 clickable TextView，desc = 应用名）。
     */
    private fun collectItems(root: AccessibilityNodeInfo, page: Int): List<DesktopItem> {
        val screenW = service.resources.displayMetrics.widthPixels
        val items = mutableListOf<DesktopItem>()
        val seen = mutableSetOf<String>()

        fun Rect.offscreen() = left >= screenW || right <= 0

        // Dock 栏和桌面搜索框的包围盒（裸模式下祖先链里没有 hotseat id，按位置判定）
        val hotseatBounds = root.findAccessibilityNodeInfosByViewId("$HOME_PKG:id/hotseat")
            .firstOrNull()?.let { Rect().also { r -> it.getBoundsInScreen(r) } }
        val searchBarBounds = root.findAccessibilityNodeInfosByViewId("$HOME_PKG:id/search_bar")
            .firstOrNull()?.let { Rect().also { r -> it.getBoundsInScreen(r) } }

        // 用中心点判定归属（边缘相交不可靠，dock 和搜索框有 1px 重叠）
        fun Rect.centerIn(r: Rect?) = r?.contains(centerX(), centerY()) == true

        // 1) 富模式
        for (icon in root.findAccessibilityNodeInfosByViewId("$HOME_PKG:id/icon_container")) {
            val item = icon.parent ?: continue
            if (!item.isVisibleToUser) continue
            val label = item.contentDescription?.toString()?.trim()
                ?.let { stripBadge(it) } ?: continue
            val rect = Rect().also { item.getBoundsInScreen(it) }
            if (rect.offscreen()) continue
            if (rect.centerIn(searchBarBounds)) continue
            val isDock = isUnderId(item, "$HOME_PKG:id/hotseat") || rect.centerIn(hotseatBounds)
            val isFolder = !isDock && item.findAccessibilityNodeInfosByViewId(
                "$HOME_PKG:id/preview_icons_container"
            ).isNotEmpty()
            if (seen.add("$label@${rect.top},${rect.left}"))
                items.add(DesktopItem(item, label, rect, isDock, isFolder))
        }

        // 2) 裸模式：desc 命中已装应用名的 clickable 节点
        fun walk(node: AccessibilityNodeInfo) {
            val desc = node.contentDescription?.toString()?.trim()
                ?.let { stripBadge(it) }
            if (!desc.isNullOrEmpty() && node.isClickable && node.isVisibleToUser &&
                desc in knownLabels
            ) {
                val rect = Rect().also { node.getBoundsInScreen(it) }
                val key = "$desc@${rect.top},${rect.left}"
                if (!rect.offscreen() && rect.width() in 100..600 &&
                    !rect.centerIn(searchBarBounds) && key !in seen
                ) {
                    seen.add(key)
                    items.add(
                        DesktopItem(node, desc, rect, rect.centerIn(hotseatBounds), false)
                    )
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { walk(it) }
            }
        }
        walk(root)

        // 网格行列：桌面项（非 dock）按 y 聚行、行内按 x 排序
        val grid = items.filter { !it.isDock }.sortedBy { it.rect.top }
        val rows = mutableListOf<MutableList<DesktopItem>>()
        for (item in grid) {
            val row = rows.lastOrNull()
            if (row != null && kotlin.math.abs(item.rect.top - row.first().rect.top) < 150) {
                row.add(item)
            } else {
                rows.add(mutableListOf(item))
            }
        }
        rows.forEachIndexed { r, rowItems ->
            rowItems.sortedBy { it.rect.left }.forEachIndexed { c, item ->
                item.row = r + 1
                item.col = c + 1
            }
        }
        return items
    }

    /** 点开文件夹，枚举里面的应用，再关掉；中断返回 false */
    private suspend fun scanFolder(
        folderItem: AccessibilityNodeInfo,
        folderLabel: String,
        page: Int,
        out: MutableList<AppLocation>,
    ): Boolean {
        if (!folderItem.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true // 点不动就跳过
        val grid = untilNode(timeoutMs = 3000) { root ->
            root?.findAccessibilityNodeInfosByViewId("$HOME_PKG:id/folder_grid_view")
                ?.firstOrNull()
        } ?: run {
            // 没打开（可能误启动了应用），按返回键兜底后跳过
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            delay(500)
            return true
        }

        // 文件夹标题以实际打开的为准（桌面 desc 一般就是它）
        val title = untilNode(1500) { root ->
            root?.findAccessibilityNodeInfosByViewId("$HOME_PKG:id/title")
                ?.firstOrNull()?.text?.toString()
        } ?: folderLabel

        // 枚举文件夹内图标，可滚动就一直翻，直到没有新内容
        val seen = linkedSetOf<String>()
        var guard = 0
        while (guard++ < 20) {
            val g = untilNode(1500) { root ->
                root?.findAccessibilityNodeInfosByViewId("$HOME_PKG:id/folder_grid_view")
                    ?.firstOrNull()
            } ?: break
            var added = false
            for (i in 0 until g.childCount) {
                val child = g.getChild(i) ?: continue
                val name = (child.contentDescription?.toString()?.trim()
                    ?: child.text?.toString()?.trim())
                    ?.let { stripBadge(it) }
                    ?: continue
                if (name.isNotEmpty() && seen.add(name)) added = true
            }
            if (!g.isScrollable || !added) break
            if (!g.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) break
            delay(450)
        }

        seen.forEachIndexed { idx, name ->
            out.add(
                AppLocation(
                    label = name, page = page, folderName = title,
                    row = null, col = null, indexInFolder = idx + 1,
                )
            )
        }
        Log.d(TAG, "folder '$title' on page $page: ${seen.size} apps -> $seen")

        // 关闭文件夹并等待回到桌面
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        val closed = untilNode(2500) { root ->
            if (root?.findAccessibilityNodeInfosByViewId("$HOME_PKG:id/folder_grid_view")
                    .isNullOrEmpty()
            ) Unit else null
        }
        if (closed == null) {
            // 再按一次返回；还不行视为中断
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            delay(500)
            if (service.rootInActiveWindow?.packageName != HOME_PKG) return false
        }
        delay(300)
        return true
    }

    private fun currentPage(): Pair<Int, Int>? {
        val root = service.rootInActiveWindow ?: return null
        if (root.packageName != HOME_PKG) return null
        val sb = findSeekBar(root) ?: return null
        val desc = sb.contentDescription?.toString() ?: return null
        val m = PAGE_REGEX.find(desc)
        if (m == null) Log.d(TAG, "seekbar desc unmatched: '$desc'")
        else Log.d(TAG, "seekbar desc: '$desc'")
        m ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    private fun findSeekBar(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.className?.toString()?.contains("SeekBar") == true &&
            node.contentDescription != null
        ) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findSeekBar(child)?.let { return it }
        }
        return null
    }

    private fun isUnderId(node: AccessibilityNodeInfo, id: String): Boolean {
        var cur: AccessibilityNodeInfo? = node
        while (cur != null) {
            if (cur.viewIdResourceName == id) return true
            cur = cur.parent
        }
        return false
    }

    private suspend fun awaitHome(): AccessibilityNodeInfo? =
        untilNode(5000) { root -> root?.takeIf { it.packageName == HOME_PKG } }

    private suspend fun <T> untilNode(
        timeoutMs: Long,
        block: (AccessibilityNodeInfo?) -> T?,
    ): T? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            block(service.rootInActiveWindow)?.let { return it }
            delay(150)
        }
        return null
    }

    private suspend fun swipe(forward: Boolean) {
        val dm = service.resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val y = dm.heightPixels * 0.55f
        val path = Path()
        if (forward) {
            path.moveTo(w * 0.8f, y); path.lineTo(w * 0.2f, y)
        } else {
            path.moveTo(w * 0.2f, y); path.lineTo(w * 0.8f, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 350))
            .build()
        suspendCancellableCoroutine<Unit> { cont ->
            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { cont.resume(Unit) }
                override fun onCancelled(g: GestureDescription?) { cont.resume(Unit) }
            }, null)
            cont.invokeOnCancellation {}
        }
    }
}
