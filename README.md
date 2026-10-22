# SearchEverything

Android 上的 "Everything"：一个搜索框找到手机里的**应用**和**文件**，还能告诉你应用图标在**桌面第几屏、哪个文件夹里**。

## 功能

- **应用搜索**：枚举全部已安装应用，支持名称 / 拼音首字母（`wx` → 微信）/ 全拼 / 包名；点击直接启动，长按跳系统应用信息页
- **模糊搜索**：编辑距离容错，输错也能命中（`discard` / `descard` → Discord）
- **文件搜索**：全盘 `/sdcard` 文件名索引（Room 本地数据库），毫秒级子串匹配；点击按类型打开，长按可分享 / 打开所在目录
- **桌面位置索引**（MIUI/HyperOS 实测）：通过无障碍服务自动翻屏、点开文件夹，记录每个应用的位置，搜索结果直接显示 `第2屏 · 文件夹「chat」第1个`

## 权限说明

| 权限 | 用途 |
|---|---|
| QUERY_ALL_PACKAGES | 枚举全部已安装应用 |
| MANAGE_EXTERNAL_STORAGE | 全盘文件名索引 |
| 无障碍服务 | 读取系统桌面的图标/文件夹布局（仅此用途，不读取其他应用内容） |

## 技术要点

- Kotlin + Jetpack Compose (Material3) + Room，minSdk 26 / targetSdk 34
- 拼音匹配：[TinyPinyin](https://github.com/promeG/TinyPinyin)
- 应用模糊匹配：子串编辑距离 DP（首行置 0，超限早退）
- 桌面扫描器（`a11y/DesktopScanner.kt`）适配 MIUI 桌面的两种图标节点结构（富模式 `icon_container` / 裸 TextView），自动剥离未读角标、识别 Dock 栏与同名文件夹

## 构建

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

调试便利：`adb shell am start -n com.search.everything/.MainActivity --ez autoscan true` 可直接触发桌面扫描。
