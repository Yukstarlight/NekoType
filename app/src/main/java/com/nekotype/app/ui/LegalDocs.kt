package com.nekotype.app.ui

import androidx.appcompat.app.AppCompatDelegate
import java.util.Locale

/**
 * 法律文本（隐私政策 / 第三方信息共享清单 / 开源许可）。
 *
 * 单独放在一个文件里，便于法务相关文案统一维护与审阅。
 * 语言跟随应用当前语言（AppCompatDelegate 的应用级语言，未设置时用系统语言）。
 *
 * 内容依据（写之前逐条核对过，避免写出不属实的承诺）：
 * - AndroidManifest 未声明 INTERNET 权限 → 应用在系统层面无法联网
 * - 权限清单：SYSTEM_ALERT_WINDOW / BIND_ACCESSIBILITY_SERVICE / FOREGROUND_SERVICE
 *   / POST_NOTIFICATIONS / REQUEST_IGNORE_BATTERY_OPTIMIZATIONS / QUERY_ALL_PACKAGES
 *   / RECEIVE_BOOT_COMPLETED
 * - 依赖：AndroidX、Material、Kotlin/coroutines、Shizuku API、argon2kt（均 Apache-2.0）
 *   + Termux 终端模拟器代码（GPL-3.0）
 */
object LegalDocs {

    private fun lang(): String {
        val tag = try {
            AppCompatDelegate.getApplicationLocales().toLanguageTags()
        } catch (_: Throwable) { "" }
        val t = tag.ifEmpty { Locale.getDefault().toString() }
        return when {
            t.startsWith("zh") && (t.contains("TW") || t.contains("Hant") || t.contains("HK")) -> "tw"
            t.startsWith("zh") -> "zh"
            else -> "en"
        }
    }

    // ---------- 隐私政策 ----------

    fun privacy(): String = when (lang()) {
        "zh" -> """
            NekoType 隐私政策
            更新日期：2026-09-24

            【一、我们不收集你的任何数据】
            本应用未申请 INTERNET（联网）权限，从系统层面就无法把任何内容发送到任何服务器。
            没有账号体系，不含广告、统计、推送、社交类 SDK。

            【二、输入内容的处理】
            为实现"打字即改写"，本应用通过安卓无障碍服务读取你当前输入框中的文本，
            仅用于按你自己配置的规则（前缀 / 后缀 / 随机 / 替换等）在本机改写并写回输入框。
            这些文本不会被记录、不会上传、不会离开本机。
            关闭无障碍服务后，本应用无法读取任何输入内容。

            【三、剪贴板】
            当目标应用拒绝直接写入文本时，本应用可能临时写入剪贴板以完成"粘贴"，
            并在完成后恢复你原来的剪贴板内容。剪贴板内容不会被保存或上传。

            【四、保存在本机的数据】
            · 规则与预设、行为与样式设置
            · 悬浮按钮位置与外观、应用黑名单
            · 使用次数统计
            · 应用内日志（用于排查问题，可在设置中一键清空）
            以上全部保存在设备本地，卸载应用即全部删除。

            【五、权限用途说明】
            · 无障碍服务：读取并改写输入框文本、为悬浮按钮提供操作能力
            · 悬浮窗：显示悬浮按钮
            · 通知：显示常驻前台服务通知
            · 忽略电池优化 / 自启动：避免后台被系统冻结导致功能失效
            · 查询已安装应用：用于应用黑名单选择、显示悬浮按钮所在应用名
            · 开机自启：重启后恢复服务
            · Shizuku / Root（可选，需你主动授权）：仅执行你主动触发的系统操作
              （如关闭电池优化、隐藏桌面图标），只执行写死的固定命令，
              不提供任意命令通道

            【六、未成年人】
            未成年人请在监护人指导下使用本应用。

            【七、变更与联系】
            本政策如有更新会在应用内展示。有任何疑问可通过项目主页反馈。
        """.trimIndent()

        "tw" -> """
            NekoType 隱私政策
            更新日期：2026-09-24

            【一、我們不收集你的任何資料】
            本應用未申請 INTERNET（連網）權限，在系統層面就無法把任何內容傳送到任何伺服器。
            沒有帳號系統，不含廣告、統計、推播、社群類 SDK。

            【二、輸入內容的處理】
            為實現「打字即改寫」，本應用透過安卓無障礙服務讀取你目前輸入框中的文字，
            僅用於依你自己設定的規則（前綴 / 後綴 / 隨機 / 替換等）在本機改寫並寫回輸入框。
            這些文字不會被記錄、不會上傳、不會離開本機。
            關閉無障礙服務後，本應用無法讀取任何輸入內容。

            【三、剪貼簿】
            當目標應用拒絕直接寫入文字時，本應用可能暫時寫入剪貼簿以完成「貼上」，
            並在完成後還原你原本的剪貼簿內容。剪貼簿內容不會被儲存或上傳。

            【四、保存在本機的資料】
            · 規則與預設、行為與樣式設定
            · 懸浮按鈕位置與外觀、應用黑名單
            · 使用次數統計
            · 應用內日誌（用於排查問題，可在設定中一鍵清空）
            以上全部保存在裝置本機，解除安裝即全部刪除。

            【五、權限用途說明】
            · 無障礙服務：讀取並改寫輸入框文字、為懸浮按鈕提供操作能力
            · 懸浮窗：顯示懸浮按鈕
            · 通知：顯示常駐前景服務通知
            · 忽略電池最佳化 / 自啟動：避免後台被系統凍結導致功能失效
            · 查詢已安裝應用：用於應用黑名單選擇、顯示懸浮按鈕所在應用名稱
            · 開機自啟：重開機後恢復服務
            · Shizuku / Root（選用，需你主動授權）：僅執行你主動觸發的系統操作
              （如關閉電池最佳化、隱藏桌面圖示），只執行寫死的固定指令，
              不提供任意指令通道

            【六、未成年人】
            未成年人請在監護人指導下使用本應用。

            【七、變更與聯絡】
            本政策如有更新會在應用內顯示。有任何疑問可透過專案首頁回饋。
        """.trimIndent()

        else -> """
            NekoType Privacy Policy
            Last updated: 2026-09-24

            [1. We collect nothing]
            This app does not request the INTERNET permission, so at the system level it cannot
            send anything to any server. There is no account system, and no ads, analytics,
            push or social SDKs are included.

            [2. How your typed text is handled]
            To rewrite text as you type, the app reads the text in the current input box through
            the Android accessibility service and rewrites it in place according to the rules you
            configured (prefix / suffix / random / replace). That text is never recorded, uploaded
            or sent off the device. With the accessibility service turned off, the app cannot read
            any input content.

            [3. Clipboard]
            If the target app refuses a direct write, the app may temporarily write to the
            clipboard to perform a paste, then restore your previous clipboard content.
            Clipboard content is never stored or uploaded.

            [4. Data stored on your device]
            · Rules and presets, behaviour and style settings
            · Floating button position/appearance, app blacklist
            · Usage counters
            · In-app logs (for troubleshooting, clearable in Settings)
            All of it stays on the device and is deleted when you uninstall the app.

            [5. Why each permission is used]
            · Accessibility service: read/rewrite input box text, drive the floating button
            · Display over other apps: show the floating button
            · Notifications: show the persistent foreground-service notification
            · Ignore battery optimization / auto-start: keep the service from being frozen
            · Query installed apps: app blacklist picker and showing the target app name
            · Boot completed: restore the service after a restart
            · Shizuku / Root (optional, you must grant it): only performs system operations you
              explicitly trigger (e.g. battery whitelist, hiding the launcher icon) using fixed,
              hard-coded commands — never an arbitrary command channel

            [6. Minors]
            Minors should use this app under guardian supervision.

            [7. Changes and contact]
            Updates to this policy will be shown in the app. Questions are welcome via the
            project page.
        """.trimIndent()
    }

    // ---------- 第三方信息共享清单 ----------

    fun thirdParty(): String = when (lang()) {
        "zh" -> """
            NekoType 第三方信息共享清单
            更新日期：2026-09-24

            【结论：不与任何第三方共享你的个人信息】
            本应用不收集设备标识（IMEI / OAID / Android ID / MAC 地址），
            不读取位置、通讯录、短信、相册，不集成广告、统计分析、推送、社交 SDK，
            不存在向第三方共享、转让或公开披露个人信息的行为。

            【随应用打包的开源组件（均为本地组件，不向第三方传输数据）】
            · AndroidX（core-ktx / appcompat / lifecycle / preference）
              提供方：Google    许可：Apache-2.0    用途：基础框架与界面
            · Material Components for Android
              提供方：Google    许可：Apache-2.0    用途：界面组件
            · Kotlin 标准库 / kotlinx.coroutines
              提供方：JetBrains    许可：Apache-2.0    用途：语言运行时与协程调度
            · Shizuku API / Provider
              提供方：Rikka    许可：Apache-2.0    用途：与本机 Shizuku 服务通信（仅本机）
            · argon2kt
              提供方：lambdapioneer    许可：Apache-2.0    用途：密码锁定的本地哈希计算
            · Termux 终端模拟器代码（com.termux.terminal）
              提供方：Termux 项目    许可：GPL-3.0    用途：内置终端实现

            【数据出境】
            不存在。应用无法联网，不会发生任何数据传输。
        """.trimIndent()

        "tw" -> """
            NekoType 第三方資訊共享清單
            更新日期：2026-09-24

            【結論：不與任何第三方共享你的個人資訊】
            本應用不收集裝置識別碼（IMEI / OAID / Android ID / MAC 位址），
            不讀取位置、通訊錄、簡訊、相簿，不整合廣告、統計分析、推播、社群 SDK，
            不存在向第三方共享、轉讓或公開揭露個人資訊的行為。

            【隨應用打包的開源元件（均為本機元件，不向第三方傳輸資料）】
            · AndroidX（core-ktx / appcompat / lifecycle / preference）
              提供方：Google    授權：Apache-2.0    用途：基礎框架與介面
            · Material Components for Android
              提供方：Google    授權：Apache-2.0    用途：介面元件
            · Kotlin 標準庫 / kotlinx.coroutines
              提供方：JetBrains    授權：Apache-2.0    用途：語言執行環境與協程排程
            · Shizuku API / Provider
              提供方：Rikka    授權：Apache-2.0    用途：與本機 Shizuku 服務通訊（僅本機）
            · argon2kt
              提供方：lambdapioneer    授權：Apache-2.0    用途：密碼鎖定的本機雜湊計算
            · Termux 終端機模擬器程式碼（com.termux.terminal）
              提供方：Termux 專案    授權：GPL-3.0    用途：內建終端機實作

            【資料跨境傳輸】
            不存在。應用無法連網，不會發生任何資料傳輸。
        """.trimIndent()

        else -> """
            NekoType Third-Party Data Sharing List
            Last updated: 2026-09-24

            [Summary: nothing is shared with any third party]
            The app does not collect device identifiers (IMEI / OAID / Android ID / MAC),
            does not read location, contacts, SMS or photos, and bundles no advertising,
            analytics, push or social SDKs. No personal information is shared with, transferred
            to, or publicly disclosed to any third party.

            [Open-source components bundled with the app — all local, no data leaves the device]
            · AndroidX (core-ktx / appcompat / lifecycle / preference)
              Provider: Google    License: Apache-2.0    Purpose: base framework and UI
            · Material Components for Android
              Provider: Google    License: Apache-2.0    Purpose: UI components
            · Kotlin standard library / kotlinx.coroutines
              Provider: JetBrains    License: Apache-2.0    Purpose: runtime and coroutines
            · Shizuku API / Provider
              Provider: Rikka    License: Apache-2.0    Purpose: talk to the local Shizuku service
            · argon2kt
              Provider: lambdapioneer    License: Apache-2.0    Purpose: local password hashing
            · Termux terminal emulator code (com.termux.terminal)
              Provider: Termux project    License: GPL-3.0    Purpose: built-in terminal

            [Cross-border transfer]
            None. The app cannot access the network, so no data transfer can occur.
        """.trimIndent()
    }

    // ---------- 开源许可 ----------

    fun licenses(): String = when (lang()) {
        "zh" -> """
            NekoType 开源许可
            更新日期：2026-09-26

            本应用基于以下开源项目构建，在此致谢并保留其原始许可：

            【Apache License 2.0】
            · AndroidX（core-ktx 1.13.1 / appcompat 1.7.0 / lifecycle 2.8.6 / preference 1.2.1）
            · Material Components for Android 1.12.0
            · Kotlin 标准库、kotlinx.coroutines 1.8.1
            · Shizuku API / Provider 13.1.5（作者 Rikka）
            · argon2kt 1.5.0（作者 lambdapioneer）
            许可全文：https://www.apache.org/licenses/LICENSE-2.0

            【GNU General Public License v3.0】
            · Termux 终端模拟器代码（com.termux.terminal 包下的终端实现）
              来源：https://github.com/termux/termux-app
              许可全文：https://www.gnu.org/licenses/gpl-3.0.html
            说明：依据 GPLv3，包含该部分代码的完整对应源码可公开获取，
                  见下方项目主页；该部分代码的修改与再分发需遵循 GPLv3。
            · Hail（雹）—— 应用冻结 / 隐藏工具
              来源：https://github.com/aistra0528/Hail
              许可全文：https://www.gnu.org/licenses/gpl-3.0.html
            说明：本应用的 Shizuku 特权通道（经 moe.shizuku.server.IShizukuService
                  直连 newProcess 起 shell 进程执行固定动作）参考其实现思路。

            【GNU Lesser General Public License v3.0】
            · Operit AI —— Android 端 AI 助手
              来源：https://github.com/AAswordman/Operit
              许可全文：https://www.gnu.org/licenses/lgpl-3.0.html
            说明：集成终端的文本选取复制交互（长按选取后经 ActionMode 弹出「复制」
                  菜单、拖动选区时的放大镜与边缘自动滚动）参考其实现思路。

            【本项目自身】
            项目主页：https://github.com/Yukstarlight/NekoType
            许可：GNU General Public License v3.0（GPL-3.0）
            许可全文：https://www.gnu.org/licenses/gpl-3.0.html

            向所有开源作者致谢。
        """.trimIndent()

        "tw" -> """
            NekoType 開源授權
            更新日期：2026-09-26

            本應用基於以下開源專案建置，在此致謝並保留其原始授權：

            【Apache License 2.0】
            · AndroidX（core-ktx 1.13.1 / appcompat 1.7.0 / lifecycle 2.8.6 / preference 1.2.1）
            · Material Components for Android 1.12.0
            · Kotlin 標準庫、kotlinx.coroutines 1.8.1
            · Shizuku API / Provider 13.1.5（作者 Rikka）
            · argon2kt 1.5.0（作者 lambdapioneer）
            授權全文：https://www.apache.org/licenses/LICENSE-2.0

            【GNU General Public License v3.0】
            · Termux 終端機模擬器程式碼（com.termux.terminal 套件下的終端機實作）
              來源：https://github.com/termux/termux-app
              授權全文：https://www.gnu.org/licenses/gpl-3.0.html
            說明：依 GPLv3，包含該部分程式碼的完整對應原始碼可公開取得，
                  見下方專案首頁；該部分程式碼的修改與再散布需遵循 GPLv3。
            · Hail（雹）—— 應用凍結 / 隱藏工具
              來源：https://github.com/aistra0528/Hail
              授權全文：https://www.gnu.org/licenses/gpl-3.0.html
            說明：本應用的 Shizuku 特權通道（經 moe.shizuku.server.IShizukuService
                  直連 newProcess 起 shell 行程執行固定動作）參考其實作思路。

            【GNU Lesser General Public License v3.0】
            · Operit AI —— Android 端 AI 助手
              來源：https://github.com/AAswordman/Operit
              授權全文：https://www.gnu.org/licenses/lgpl-3.0.html
            說明：整合終端的文字選取複製互動（長按選取後經 ActionMode 彈出「複製」
                  選單、拖動選取區時的放大鏡與邊緣自動捲動）參考其實作思路。

            【本專案自身】
            專案首頁：https://github.com/Yukstarlight/NekoType
            授權：GNU General Public License v3.0（GPL-3.0）
            授權全文：https://www.gnu.org/licenses/gpl-3.0.html

            向所有開源作者致謝。
        """.trimIndent()

        else -> """
            NekoType Open Source Licenses
            Last updated: 2026-09-26

            This app is built on the following open-source projects. Thanks to their authors;
            their original licenses are retained.

            [Apache License 2.0]
            · AndroidX (core-ktx 1.13.1 / appcompat 1.7.0 / lifecycle 2.8.6 / preference 1.2.1)
            · Material Components for Android 1.12.0
            · Kotlin standard library, kotlinx.coroutines 1.8.1
            · Shizuku API / Provider 13.1.5 (by Rikka)
            · argon2kt 1.5.0 (by lambdapioneer)
            Full text: https://www.apache.org/licenses/LICENSE-2.0

            [GNU General Public License v3.0]
            · Termux terminal emulator code (the terminal implementation under com.termux.terminal)
              Source: https://github.com/termux/termux-app
              Full text: https://www.gnu.org/licenses/gpl-3.0.html
            Note: under GPLv3 the complete corresponding source for that part is publicly
                  available at the project page below; modifications and redistribution of that
                  part must follow GPLv3.
            · Hail - app freezer / hider
              Source: https://github.com/aistra0528/Hail
              Full text: https://www.gnu.org/licenses/gpl-3.0.html
            Note: this app's Shizuku privileged channel (directly calling
                  moe.shizuku.server.IShizukuService#newProcess to run fixed actions in a
                  shell process) follows its implementation approach.

            [GNU Lesser General Public License v3.0]
            · Operit AI - Android AI assistant
              Source: https://github.com/AAswordman/Operit
              Full text: https://www.gnu.org/licenses/lgpl-3.0.html
            Note: the integrated terminal's text-selection copy interaction (long-press to
                  select, then an ActionMode menu with "Copy", plus magnifier and edge
                  auto-scroll while dragging) follows its implementation approach.

            [This project]
            Project page: https://github.com/Yukstarlight/NekoType
            License: GNU General Public License v3.0 (GPL-3.0)
            Full text: https://www.gnu.org/licenses/gpl-3.0.html

            Thanks to every open-source author.
        """.trimIndent()
    }
}
