package com.search.everything.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import com.github.promeg.pinyinhelper.Pinyin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppInfo(
    val label: String,
    val packageName: String,
    val pinyinInitials: String, // 每个汉字拼音首字母，如 "微信" -> "wx"
    val pinyinFull: String,     // 全拼，如 "weixin"
    val icon: Drawable,
)

class AppRepository(private val context: Context) {

    suspend fun loadApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { ri ->
                val label = ri.loadLabel(pm).toString()
                AppInfo(
                    label = label,
                    packageName = ri.activityInfo.packageName,
                    pinyinInitials = label.toPinyinInitials(),
                    pinyinFull = label.toPinyinFull(),
                    icon = ri.loadIcon(pm),
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    /** 返回 (app, 匹配分)，分数越小排越前；不匹配返回 null */
    fun matchScore(app: AppInfo, query: String): Int? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        val label = app.label.lowercase()
        return when {
            label.startsWith(q) -> 0
            label.contains(q) -> 1
            app.pinyinInitials.startsWith(q) -> 2
            app.pinyinFull.startsWith(q) -> 3
            app.pinyinInitials.contains(q) -> 4
            app.pinyinFull.contains(q) -> 5
            app.packageName.lowercase().contains(q) -> 6
            // 模糊匹配：容许少量编辑错误，如 discard/descard 命中 discord
            fuzzyAllowed(q) && (
                fuzzyContains(label, q, fuzzyMaxDist(q)) ||
                fuzzyContains(app.pinyinFull, q, fuzzyMaxDist(q))
            ) -> 7
            else -> null
        }
    }

    private fun fuzzyAllowed(q: String) = q.length >= 3

    private fun fuzzyMaxDist(q: String) = if (q.length >= 5) 2 else 1

    /**
     * 子串编辑距离：query 能否以 ≤maxDist 的编辑距离匹配 text 的某段子串。
     * 标准 DP，首行初始化为 0（text 开头不计代价），
     * 任何 DP 行最小值超过 maxDist 即提前失败。
     */
    fun fuzzyContains(text: String, query: String, maxDist: Int): Boolean {
        val n = text.length
        val m = query.length
        if (m == 0) return true
        if (n == 0) return false
        var prev = IntArray(n + 1) // 首行全 0：匹配可从 text 任意位置开始
        for (i in 1..m) {
            val cur = IntArray(n + 1) { i } // 首列 = i（query 前缀全删）
            var rowMin = Int.MAX_VALUE
            val qc = query[i - 1]
            for (j in 1..n) {
                val cost = if (text[j - 1] == qc) 0 else 1
                cur[j] = minOf(
                    prev[j] + 1,       // 删除 query 字符
                    cur[j - 1] + 1,    // 插入
                    prev[j - 1] + cost // 替换/相等
                )
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > maxDist) return false
            prev = cur
        }
        return (1..n).any { prev[it] <= maxDist }
    }

    fun launch(packageName: String) {
        context.packageManager.getLaunchIntentForPackage(packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(it)
        }
    }

    fun openAppDetails(packageName: String) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun String.toPinyinInitials(): String = buildString(length) {
        for (c in this@toPinyinInitials) {
            if (Pinyin.isChinese(c)) append(Pinyin.toPinyin(c).first().lowercaseChar())
            else if (c.isLetterOrDigit()) append(c.lowercaseChar())
        }
    }

    private fun String.toPinyinFull(): String = buildString(length * 4) {
        for (c in this@toPinyinFull) {
            if (Pinyin.isChinese(c)) append(Pinyin.toPinyin(c).lowercase())
            else if (c.isLetterOrDigit()) append(c.lowercaseChar())
        }
    }
}
