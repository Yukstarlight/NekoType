NekoType

> Android 系统级消息文本自定义工具 —— 悬浮按钮一键为输入框内容添加自定义前缀/后缀/随机尾缀/文本替换，改写后自动发送[]()[]()[](LICENSE)

NekoType 是一款运行在 Android 上的系统级消息处理工具，核心目标只有一个：**让用户以最小的操作成本，把聊天输入框里的文本按照自定义规则改写后一键发送**。你正常打字，点击屏幕边缘常驻的悬浮按钮，消息会自动完成「文本替换 → 前缀 → 后缀 → 随机尾缀 → 自动发送」的完整流程。例如配置前缀为 `回复：`、后缀为 `（已读）`，输入 `好的`，发出的就是 `回复：好的（已读）`；开启随机模式后，每条消息还会按概率追加不同的随机尾缀，让内容不再千篇一律。

它不是简单的输入法皮肤或聊天美化插件，而是一套完整的**系统能力集成方案**：通过 Android 无障碍服务感知并读写当前输入框文本，通过系统悬浮窗提供全局触达入口，通过 Shizuku / Root 获得系统级命令执行能力（如直接写入电池优化白名单，保证后台常驻）。项目内置三种运行模式——**基础模式 / Shizuku 模式 / Root 模式**——按需切换，核心功能最低仅需「悬浮窗 + 无障碍」两项权限即可跑通，绝大多数用户开箱即用，无需 Root。

## ✨ 核心特性

### 文本变换引擎

* **固定前缀**：前缀填 `回复：`，输入 `好的` → 发送 `回复：好的`
* **固定后缀**：后缀填 `（已读）`，输入 `好的` → 发送 `好的（已读）`
* **随机前缀 / 随机后缀**：从自定义池中按**出现概率**（默认 50%，可设 1–100%）随机追加，固定与随机**可叠加**生效，杜绝"每次必触发"的无脑随机
* **文本替换**：内置常用中日替换（`的→の`、`是→です` 等），支持每行一条 `原文->替换` 的自定义规则
* **样式变换**：字符间加空格、转为大写
* **效果预览**：App 内聊天气泡式实时预览，调参所见即所得

### 悬浮按钮

* **永久常驻**屏幕边缘（默认右侧居中），点击带水波纹反馈，图标可替换为任意图片
* 按住拖动自由摆放、自动**边缘吸附**、位置持久记忆
* Android 12+ 自动启用背景模糊（毛玻璃）
* 点击 = 变换 + 发送（可关闭自动发送，仅改写文本）；常驻通知栏，一键停止

### 规则系统

* 多套规则自由**选择 / 删除**，每套规则拥有独立的：前缀、后缀、随机池、出现概率、替换规则、样式
* 一键切换不同使用场景（工作 / 群聊 / 社交），无需反复改配置

### 智能发送

三级兜底策略，兼容主流聊天应用：

1. 在无障碍节点树中查找「发送」按钮（按文本 / 控件 ID / contentDescription 启发式匹配）→ `ACTION_CLICK`
2. 回退 IME 发送动作（`ACTION_IME_ACTION_SEND`）
3. 再失败使用全局手势点击发送键屏幕坐标

### 权限与系统能力

| 能力  | 用途  | 获取方式 |
| --- | --- | --- |
| 悬浮窗 | 显示悬浮按钮 | 系统设置授权 |
| 无障碍 | 读写输入框 / 触发发送 | 系统设置开启 |
| 关闭电池优化 | 后台常驻不被杀 | 系统弹窗 **或** Shizuku/Root 直写白名单 |
| Shizuku | 系统级 shell 命令（免 Root） | 安装 Shizuku + 无线调试/ADB 授权 |
| 设备管理员 | 额外系统能力（可选） | 系统设置激活 |
| Root | 最高权限命令执行 | Magisk / KernelSU / APatch |

内置**五步权限引导向导**：无障碍 → 设备管理员 → 关闭省电优化 → 显示在应用上层 → Shizuku 授权（或进入基础模式），逐项点亮状态，全部就绪后悬浮按钮永久常驻。

## 🔧 三种运行模式

| 模式  | 权限依赖 | 系统命令（如免电白名单） |
| --- | --- | --- |
| **基础模式**（默认） | 仅悬浮窗 + 无障碍 | 不执行，核心变换/发送功能完整可用 |
| **Shizuku 模式** | 悬浮窗 + 无障碍 + Shizuku 授权 | 经 Shizuku UserService（shell 权限）执行 |
| **Root 模式** | 悬浮窗 + 无障碍 + Root | 经 `su` 执行（Magisk / KernelSU / APatch） |

## 🛠 技术原理

| 能力  | 实现方式 |
| --- | --- |
| 读取 / 改写其他 App 的 EditText | `AccessibilityNodeInfo.ACTION_SET_TEXT`（官方无障碍 API，带验证重试） |
| 触发发送 | 三级策略：发送按钮点击 → IME 动作 → 全局手势 |
| 悬浮按钮 | `TYPE_APPLICATION_OVERLAY` + `FLAG_ALT_FOCUSABLE_IM`，拖放 + 位置持久化 + 边缘吸附 |
| 系统命令执行 | Root（`su`）或 Shizuku UserService（**Messenger 方案**，Shizuku 13 已移除 `newProcess`） |
| 全局感知 | `AccessibilityService` 跟踪聚焦输入框（`TYPE_VIEW_FOCUSED` / `TYPE_VIEW_TEXT_CHANGED`） |

全部基于 Android 官方公开 API 实现，**无私有 API、无 Xposed、无 Hook**。所有文本处理 100% 在设备本地完成，不联网、不上传任何输入内容。

## 🚀 使用方式

1. 安装后打开 App → 启动服务 → 按权限引导顺序授予权限（无障碍 + 悬浮窗为必需，其余可选）。
2. 悬浮按钮常驻屏幕边缘；在任意聊天 App 输入文字后点击按钮，文本自动变换并发送。
3. 在「规则」页配置前缀 / 后缀 / 随机池 / 概率 / 替换规则，可多套规则切换。
4. 右上角设置：更换运行模式、查看详细信息 / 版本号、意见反馈、赞助支持。

## ⚙️ 构建

### Windows（一键脚本）

    # 前置：JDK 17+、Android SDK（ANDROID_HOME 或默认 %LOCALAPPDATA%\Android\Sdk）
    powershell -ExecutionPolicy Bypass -File sign_and_build.ps1
    # 产物: app\build\outputs\apk\release\app-release.apk （已签名）

### Linux / macOS

    chmod +x sign_and_build.sh
    ./sign_and_build.sh

### 手动构建

    git clone https://github.com/<you>/NekoType.git
    cd NekoType
    ./gradlew assembleRelease

要求：JDK 17+，Android SDK Platform 34 + Build-Tools 34.0.0，Gradle 8.7（仓库已内置 wrapper）。

## 🔐 签名说明

* Release 与 Debug 共用同一签名，避免按签名校验的应用 debug/release 不一致。
* `nekotype-release.keystore` 与 `keystore.properties` 已 gitignore，**切勿提交**；默认密码仅开箱即用，正式发布前务必更换强密码并妥善保管。
* CI 构建见 [.github/workflows/build.yml](.github/workflows/build.yml)，可通过仓库 Secrets 注入密钥。

## 📬 支持与反馈

* QQ 交流群：`1007865515`
* 邮箱：`TR114512@qq.com`
* 喜欢本项目的话欢迎点 ⭐ 支持，赞助请见 App 内「支持与反馈」页的赞助码。

## ⚠️ 隐私与安全

* 所有文本处理均在**设备本地**完成，不联网、不上传任何输入内容。
* 无障碍权限仅用于读取/改写当前输入框文本并触发发送，不读取密码框（`isPassword = true` 的节点无障碍本身也无法获取文本）。
* 本工具为个人效率 / 趣味性用途，请勿在他人设备上未经同意安装使用。

## 📄 License

MIT © NekoType
