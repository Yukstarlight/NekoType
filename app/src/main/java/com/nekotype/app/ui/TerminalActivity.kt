package com.nekotype.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.nekotype.app.R
import com.nekotype.app.prefs.AppPrefs
import com.nekotype.app.util.NekoLang
import com.nekotype.app.util.NekoLog
import com.nekotype.app.util.ThemeHelper
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File

/**
 * 内嵌终端页（MUIT 集成终端 Beta）：
 * 复用 Termux 的 terminal-emulator / terminal-view 引擎，通过 JNI PTY 跑 Android 系统 shell。
 */
class TerminalActivity : AppCompatActivity(), TerminalSessionClient, TerminalViewClient {

    private var terminalView: TerminalView? = null
    private var session: TerminalSession? = null
    private lateinit var prefs: SharedPreferences
    private var quickCmdExpanded = false

    companion object {
        private const val COPY_MENU_ID = 1
        private const val PREFS = "terminal_settings"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_ALPHA = "panel_alpha"
        private const val KEY_TEXT_COLOR = "text_color"
        private const val DEFAULT_FONT_SIZE = 14
        private const val DEFAULT_ALPHA = 50
        private const val DEFAULT_TEXT_COLOR = 0xFFFFFFFF.toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.apply(this)
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LOW_PROFILE
        setContentView(com.nekotype.app.R.layout.activity_terminal)
        // 应用自定义背景（优先用户自定义壁纸，无则回退主题背景）
        com.nekotype.app.util.BgUtils.apply(findViewById(android.R.id.content))
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val tv = findViewById<com.termux.view.TerminalView>(com.nekotype.app.R.id.terminalView)
        terminalView = tv
        tv.setTerminalViewClient(this)

        // 关闭按钮
        findViewById<com.google.android.material.button.MaterialButton>(com.nekotype.app.R.id.btnCloseTerminal)?.setOnClickListener {
            finish()
        }

        // 设置按钮：字体大小 / 透明度调节
        findViewById<com.google.android.material.button.MaterialButton>(com.nekotype.app.R.id.btnTerminalSettings)?.setOnClickListener {
            showTerminalSettingsDialog()
        }

        // 补全按钮：手机无Tab键，点击对当前输入做命令补全
        findViewById<com.google.android.material.button.MaterialButton>(com.nekotype.app.R.id.btnTabComplete)?.setOnClickListener {
            session?.let { handleTabCompletion(it) }
        }

        // 读取保存的设置并应用
        val fontSize = prefs.getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE)
        val alpha = prefs.getInt(KEY_ALPHA, DEFAULT_ALPHA)
        val textColor = prefs.getInt(KEY_TEXT_COLOR, DEFAULT_TEXT_COLOR)
        applyTerminalSettings(fontSize, alpha, textColor)

        tv.setBackgroundColor(Color.TRANSPARENT)
        tv.isFocusable = true
        tv.isFocusableInTouchMode = true
        tv.requestFocus()

        // 快捷命令面板
        setupQuickCmdPanel()

        startShell()
    }

    /** 应用终端设置：字体大小 + 面板透明度 + 字体颜色 */
    private fun applyTerminalSettings(fontSize: Int, alpha: Int, textColor: Int = DEFAULT_TEXT_COLOR) {
        val tv = terminalView ?: return
        val fontSizePx = (fontSize * resources.displayMetrics.density).toInt()
        tv.setTextSize(fontSizePx)
        try {
            val tf = android.graphics.Typeface.createFromAsset(assets, "fonts/jetbrains_mono.ttf")
            tv.setTypeface(tf)
        } catch (_: Throwable) {
            tv.setTypeface(android.graphics.Typeface.MONOSPACE)
        }
        val a = (alpha * 255 / 100).coerceIn(0, 255)
        findViewById<View>(com.nekotype.app.R.id.terminalTitleBar)?.setBackgroundColor(Color.argb(a, 0, 0, 0))
        findViewById<View>(com.nekotype.app.R.id.quickCmdPanel)?.setBackgroundColor(Color.argb(a, 0, 0, 0))
        // 应用字体颜色到终端会话
        try {
            val emulator = session?.emulator
            emulator?.mColors?.mCurrentColors?.set(
                com.termux.terminal.TextStyle.COLOR_INDEX_FOREGROUND,
                textColor
            )
            tv.onScreenUpdated()
        } catch (_: Throwable) { }
    }

    /** 终端设置对话框：字体大小 + 透明度 + 字体颜色 */
    private fun showTerminalSettingsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(com.nekotype.app.R.layout.dialog_terminal_settings, null)
        val seekFont = dialogView.findViewById<SeekBar>(com.nekotype.app.R.id.seekFontSize)
        val seekAlpha = dialogView.findViewById<SeekBar>(com.nekotype.app.R.id.seekAlpha)
        val tvFontVal = dialogView.findViewById<TextView>(com.nekotype.app.R.id.tvFontSizeVal)
        val tvAlphaVal = dialogView.findViewById<TextView>(com.nekotype.app.R.id.tvAlphaVal)
        val tvColorVal = dialogView.findViewById<TextView>(com.nekotype.app.R.id.tvColorVal)

        val curFont = prefs.getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE)
        val curAlpha = prefs.getInt(KEY_ALPHA, DEFAULT_ALPHA)
        var curColor = prefs.getInt(KEY_TEXT_COLOR, DEFAULT_TEXT_COLOR)
        seekFont.progress = curFont - 10
        seekAlpha.progress = curAlpha
        tvFontVal.text = "$curFont sp"
        tvAlphaVal.text = "$curAlpha%"
        tvColorVal.text = getString(com.nekotype.app.R.string.i29, Integer.toHexString(curColor).uppercase())

        // 当前选中的颜色按钮
        var selectedColorView: View? = null

        fun selectColor(view: View, color: Int) {
            // 恢复上一个选中按钮的普通背景
            selectedColorView?.setBackgroundResource(com.nekotype.app.R.drawable.bg_color_circle)
            view.setBackgroundResource(com.nekotype.app.R.drawable.bg_color_circle_selected)
            selectedColorView = view
            curColor = color
            tvColorVal.text = getString(com.nekotype.app.R.string.i29, Integer.toHexString(color).uppercase())
            applyTerminalSettings(seekFont.progress + 10, seekAlpha.progress, color)
        }

        // 预设颜色按钮
        val colorIds = listOf(
            com.nekotype.app.R.id.colorWhite,
            com.nekotype.app.R.id.colorGreen,
            com.nekotype.app.R.id.colorYellow,
            com.nekotype.app.R.id.colorCyan,
            com.nekotype.app.R.id.colorPink,
            com.nekotype.app.R.id.colorOrange
        )
        colorIds.forEach { id ->
            val v = dialogView.findViewById<View>(id)
            val tag = v.tag as? String ?: return@forEach
            val color = android.graphics.Color.parseColor(tag)
            v.setOnClickListener { selectColor(v, color) }
            if (color == curColor) selectColor(v, color)
        }

        // 自定义颜色
        dialogView.findViewById<View>(com.nekotype.app.R.id.colorCustom)?.setOnClickListener {
            val et = EditText(this).apply {
                setText("#${Integer.toHexString(curColor).uppercase()}")
                setSelection(text.length)
                hint = "#RRGGBB"
            }
            NekoDialog.builder(this)
                .setTitle(getString(R.string.hc_custom_color))
                .setView(et)
                .setPositiveButton(getString(R.string.u71)) { _, _ ->
                    try {
                        val input = et.text.toString().trim()
                        val color = if (input.startsWith("#")) android.graphics.Color.parseColor(input)
                        else android.graphics.Color.parseColor("#$input")
                        selectColor(dialogView.findViewById(com.nekotype.app.R.id.colorCustom), color)
                    } catch (_: Throwable) {
                        Toast.makeText(this, getString(R.string.hc_color_format_err), Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(getString(R.string.u72), null)
                .show()
        }

        seekFont.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                val size = v + 10
                tvFontVal.text = "$size sp"
                applyTerminalSettings(size, seekAlpha.progress, curColor)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        seekAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                tvAlphaVal.text = "$v%"
                applyTerminalSettings(seekFont.progress + 10, v, curColor)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // 停止猫轰炸按钮
        dialogView.findViewById<com.google.android.material.button.MaterialButton>(com.nekotype.app.R.id.btnStopBomb)?.setOnClickListener {
            try {
                // 先发 Ctrl+C 中断正在运行的 while 循环，再发 catstop 重置变量
                // 只发 catstop 会被运行中的循环缓冲住，无法生效
                session?.write("\u0003")       // Ctrl+C → SIGINT，中断当前命令
                session?.write("catstop\n")   // 重置 __CAT_BOMB_RUN=0
                Toast.makeText(this, getString(R.string.hc_stop_cmd_sent), Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) { }
        }

        NekoDialog.builder(this)
            .setTitle(getString(R.string.hc_terminal_settings))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.u89)) { _, _ ->
                val size = seekFont.progress + 10
                val a = seekAlpha.progress
                prefs.edit()
                    .putInt(KEY_FONT_SIZE, size)
                    .putInt(KEY_ALPHA, a)
                    .putInt(KEY_TEXT_COLOR, curColor)
                    .apply()
                applyTerminalSettings(size, a, curColor)
                Toast.makeText(this, getString(R.string.hc_settings_saved), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.u72), null)
            .show()
    }

    /** 初始化快捷命令面板 */
    private fun setupQuickCmdPanel() {
        val toggleBtn = findViewById<TextView>(com.nekotype.app.R.id.btnToggleQuickCmd)
        val scroll = findViewById<View>(com.nekotype.app.R.id.quickCmdScroll)
        toggleBtn.setOnClickListener {
            quickCmdExpanded = !quickCmdExpanded
            scroll.visibility = if (quickCmdExpanded) View.VISIBLE else View.GONE
            toggleBtn.text = if (quickCmdExpanded) getString(R.string.hc_quick_cmd_collapse) else getString(R.string.hc_quick_cmd_expand)
        }
        val cmdMap = mapOf(
            com.nekotype.app.R.id.cmdLs to "ls",
            com.nekotype.app.R.id.cmdLsLa to "ls -la",
            com.nekotype.app.R.id.cmdCdSdcard to "cd /sdcard",
            com.nekotype.app.R.id.cmdPwd to "pwd",
            com.nekotype.app.R.id.cmdPs to "ps -A",
            com.nekotype.app.R.id.cmdDf to "df -h",
            com.nekotype.app.R.id.cmdClear to "clear",
            com.nekotype.app.R.id.cmdHelp to "help",
            com.nekotype.app.R.id.cmdExit to "exit",
            com.nekotype.app.R.id.cmdDate to "date",
            com.nekotype.app.R.id.cmdWhoami to "whoami",
            com.nekotype.app.R.id.cmdUname to "uname -a",
            com.nekotype.app.R.id.cmdNekosay to "nekosay 喵~",
            com.nekotype.app.R.id.cmdFortune to "fortune",
            com.nekotype.app.R.id.cmdNekomood to "nekomood",
            com.nekotype.app.R.id.cmdCatbomb to "catbomb 5",
            com.nekotype.app.R.id.cmdCatstop to "catstop",
            com.nekotype.app.R.id.cmdNeko to "neko status"
        )
        cmdMap.forEach { (id, cmd) ->
            findViewById<TextView>(id)?.setOnClickListener {
                sendCommand(cmd)
            }
        }
    }

    /** 向终端发送命令并执行 */
    private fun sendCommand(cmd: String) {
        try {
            session?.write(cmd + "\n")
            terminalView?.requestFocus()
        } catch (e: Throwable) {
            Toast.makeText(this, getString(R.string.hc_cmd_send_fail, e.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }

    /** Tab 自动补全：读取当前输入行，匹配命令列表 */
    private fun handleTabCompletion(session: TerminalSession): Boolean {
        try {
            val emulator = session.emulator ?: return false
            val row = emulator.cursorRow
            val col = emulator.cursorCol
            if (col <= 0) return false
            // 读取当前行从行首到光标位置的文本
            val line = emulator.screen.getSelectedText(0, row, col, row).trimEnd()
            if (line.isEmpty()) return false
            // 提取最后一个单词（命令前缀）
            val prefix = line.substringAfterLast(' ').trim()
            if (prefix.isEmpty()) return false
            // 补全命令列表（系统命令 + NekoType 趣味命令）
            val commands = listOf(
                "ls", "cd", "pwd", "ps", "clear", "help", "exit", "date", "whoami", "uname",
                "df", "cat", "echo", "grep", "find", "mkdir", "rm", "cp", "mv", "chmod",
                "netstat", "ifconfig", "ping", "curl", "tar", "zip", "unzip", "sleep", "kill",
                "top", "free", "uptime", "dmesg", "logcat", "getprop", "setprop", "mount",
                "sync", "md5sum", "sha256sum", "base64", "hexdump", "strings", "strace",
                "lsof", "history", "which", "who", "tty", "true", "false", "timeout",
                "nice", "renice", "nohup", "setsid", "touch", "ln", "chown", "du", "head",
                "tail", "less", "more", "file", "stat", "sed", "awk", "wc", "sort", "uniq",
                "tr", "cut", "diff", "cmp", "patch", "comm", "expand", "unexpand", "split",
                "vi", "tee", "xargs", "printf", "env", "printenv", "gzip", "gunzip", "cpio",
                "killall", "pkill", "pidof", "swapon", "swapoff", "start", "stop", "traceroute",
                "wget", "nslookup", "sha1sum", "sha512sum", "xxd", "od", "lspci", "lsusb",
                "microcom", "ionice", "taskset", "chrt", "toybox",
                // NekoType 趣味命令
                "nekosay", "fortune", "nekomood", "catbomb", "catstop", "nekohelp", "neko",
                "commands", "cls", "ll", "la"
            )
            val matches = commands.filter { it.startsWith(prefix) && it != prefix }
            if (matches.isEmpty()) return false
            if (matches.size == 1) {
                // 唯一匹配：直接补全
                session.write(matches[0].removePrefix(prefix))
                return true
            }
            // 多个匹配：换行显示所有匹配项
            session.write("\n")
            matches.forEachIndexed { i, cmd ->
                session.write(cmd)
                if (i < matches.size - 1) session.write("  ")
            }
            session.write("\n")
            return true
        } catch (_: Throwable) {
            return false
        }
    }

    private fun startShell() {
        try {
            val tv = terminalView
            if (tv == null) { finish(); return }
            // 1. 横幅作为 mksh 的 ENV rc 文件：内容必须是合法 shell 命令（每行都会被执行）
            val rcFile = File(filesDir, "muit_profile")
            try {
                val isNeko = AppPrefs.nekoMode
                val lines = listOf(
                    // ===== 全局变量 =====
                    "__CAT_BOMB_RUN=0",
                    // ===== 欢迎横幅 =====
                    "echo '========================================'",
                    if (isNeko) "echo '   Welcome to MUIT 终端喵~  BEAT 3'" else "echo '     Welcome to Mist Unveils Infinite Tomorrows \u96c6\u6210\u7ec8\u7aef  BEAT 3'",
                    if (isNeko) "echo '   Android Linux - sh + toybox 喵~'" else "echo '     Android Linux - sh + toybox'",
                    "echo '========================================'",
                    "echo ''",
                    if (isNeko) "echo '  喵~ 输入 help 查看命令列表喵~'" else "echo '  help   \u67e5\u770b\u5206\u7c7b\u547d\u4ee4\u5217\u8868'",
                    if (isNeko) "echo '  喵~ 输入 nekosay 让小猫说话喵~'" else "echo '  clear  \u6e05\u5c4f'",
                    if (isNeko) "echo '  喵~ 输入 fortune 抽今日签文喵~'" else "echo '  exit   \u9000\u51fa\u7ec8\u7aef'",
                    "echo ''",
                    // ===== 启动彩蛋：随机 ASCII 小猫 =====
                    "__egg=\$((RANDOM % 5))",
                    "case \$__egg in",
                    "  0) echo '   /\\_/\\   欢迎回来~'; echo '  ( o.o )  今天也要加油哦'; echo '   > ^ <';;",
                    "  1) echo '   /\\_/\\   喵~'; echo '  ( =^.^= )  你来啦'; echo '   > ^ <   等你好久了';;",
                    "  2) echo '   /\\_/\\   ZzZ'; echo '  ( -.- )  好困...'; echo '   > ^ <   再睡五分钟';;",
                    "  3) echo '   /\\_/\\   ✨'; echo '  ( >.< )  惊喜！'; echo '   > ^ <   发现隐藏彩蛋';;",
                    "  4) echo '   /\\_/\\   ☕'; echo '  ( o.o )  来杯咖啡?'; echo '   > ^ <   提提神吧';;",
                    "esac",
                    "echo ''",
                    // ===== help 函数：分类命令列表 =====
                    "help() {",
                    "  echo ''",
                    "  echo '--- [\u6587\u4ef6\u7ba1\u7406] ---'",
                    "  echo '  ls  cd  pwd  cp  mv  rm  mkdir  rmdir'",
                    "  echo '  touch  ln  chmod  chown  find  du  df'",
                    "  echo '  cat  head  tail  less  more  file  stat'",
                    "  echo ''",
                    "  echo '--- [\u6587\u672c\u5904\u7406] ---'",
                    "  echo '  grep  egrep  fgrep  sed  awk  echo'",
                    "  echo '  wc  sort  uniq  tr  cut  diff  cmp'",
                    "  echo '  patch  comm  expand  unexpand  split'",
                    "  echo '  vi  tee  xargs  printf  env  printenv'",
                    "  echo ''",
                    "  echo '--- [\u538b\u7f29\u6253\u5305] ---'",
                    "  echo '  tar  gzip  gunzip  zip  unzip  cpio'",
                    "  echo ''",
                    "  echo '--- [\u7cfb\u7edf\u7ba1\u7406] ---'",
                    "  echo '  ps  top  kill  killall  pkill  pidof'",
                    "  echo '  free  uptime  uname  date  whoami  id'",
                    "  echo '  dmesg  logcat  getprop  setprop  sendevent'",
                    "  echo '  insmod  rmmod  lsmod  mount  umount'",
                    "  echo '  swapon  swapoff  sync  start  stop'",
                    "  echo ''",
                    "  echo '--- [\u7f51\u7edc\u5de5\u5177] ---'",
                    "  echo '  netstat  ifconfig  ip  ping  nc  netcat'",
                    "  echo '  traceroute  curl  wget  nslookup  nbd-client'",
                    "  echo ''",
                    "  echo '--- [\u5f00\u53d1\u8c03\u8bd5] ---'",
                    "  echo '  md5sum  sha1sum  sha256sum  sha512sum'",
                    "  echo '  hexdump  xxd  od  strings  base64'",
                    "  echo '  strace  lsof  lspci  lsusb  microcom'",
                    "  echo ''",
                    "  echo '--- [\u5176\u4ed6] ---'",
                    "  echo '  clear  history  which  who  tty  true'",
                    "  echo '  false  sleep  timeout  nice  renice'",
                    "  echo '  ionice  taskset  nohup  setsid  chrt'",
                    "  echo ''",
                    "  echo '  toybox            \u67e5\u770b\u7cfb\u7edf\u5185\u7f6e\u5168\u90e8\u547d\u4ee4'",
                    "  echo '  toybox <\u547d\u4ee4> --help  \u67e5\u770b\u547d\u4ee4\u7528\u6cd5'",
                    "  echo ''",
                    "}",
                    // ===== nekosay：小猫说话 =====
                    "nekosay() {",
                    "  local msg=\"\$*\"",
                    "  if [ -z \"\$msg\" ]; then msg=\"喵~\"; fi",
                    "  local len=\${#msg}",
                    "  local top=\"\"",
                    "  local i=0",
                    "  while [ \$i -lt \$((len+2)) ]; do top=\"\$top-\"; i=\$((i+1)); done",
                    "  echo \" \$top\"",
                    "  echo \"< \$msg >\"",
                    "  echo \" \$top\"",
                    "  echo '        \\'",
                    "  echo '         \\'",
                    "  echo '            /\\_/\\'",
                    "  echo '           ( o.o )'",
                    "  echo '            > ^ <'",
                    "  echo '           /       \\'",
                    "  echo '          (         )'",
                    "  echo '           \\~^~^~^~/'",
                    "}",
                    // ===== fortune：今日签文 =====
                    "fortune() {",
                    "  local n=\$((RANDOM % 20))",
                    "  case \$n in",
                    "    0) echo '今天也是充满希望的一天喵~';;",
                    "    1) echo '人生苦短，及时行乐';;",
                    "    2) echo '只要活着，就会有好事发生';;",
                    "    3) echo '不要低头，皇冠会掉';;",
                    "    4) echo '今天的你也很棒哦';;",
                    "    5) echo '万事胜意，未来可期';;",
                    "    6) echo '愿你历尽千帆，归来仍是少年';;",
                    "    7) echo '生活明朗，万物可爱';;",
                    "    8) echo '你是人间四月天';;",
                    "    9) echo '星光不问赶路人，时光不负有心人';;",
                    "    10) echo '愿你眼中有光，心中有爱';;",
                    "    11) echo '所有的美好，都值得等待';;",
                    "    12) echo '做自己的太阳，无需凭借谁的光';;",
                    "    13) echo '愿你被这个世界温柔以待';;",
                    "    14) echo '今天也要元气满满哦';;",
                    "    15) echo '慢慢来，比较快';;",
                    "    16) echo '心之所向，素履以往';;",
                    "    17) echo '人间值得，未来可期';;",
                    "    18) echo '你若盛开，清风自来';;",
                    "    19) echo '愿你走出半生，归来仍是少年';;",
                    "  esac",
                    "}",
                    // ===== nekomood：情绪小猫 =====
                    "nekomood() {",
                    "  local mood=\"\$1\"",
                    "  local msg=\"\$2\"",
                    "  # 隐藏狂暴猫娘：未指定情绪时，按概率触发",
                    "  if [ -z \"\$mood\" ]; then",
                    "    local prob=\"\${NEKO_MOOD_PROB:-30}\"",
                    "    local roll=\$((RANDOM % 100))",
                    "    if [ \$roll -lt \$prob ]; then",
                    "      mood='berserk'",
                    "    else",
                    "      local n=\$((RANDOM % 4))",
                    "      case \$n in",
                    "        0) mood='happy';;",
                    "        1) mood='sleepy';;",
                    "        2) mood='angry';;",
                    "        3) mood='shy';;",
                    "      esac",
                    "    fi",
                    "  fi",
                    "  if [ -z \"\$msg\" ]; then msg=\"喵~\"; fi",
                    "  case \"\$mood\" in",
                    "    happy)",
                    "      echo '  \\/\\  /\\ '",
                    "      echo ' ( ^.^ )  '",
                    "      echo '  > ^ <   \$msg'",
                    "      echo '  \\~^~^~/' ",
                    "      ;;",
                    "    sleepy)",
                    "      echo '  \\/\\  /\\ '",
                    "      echo ' ( -.- )zZ'",
                    "      echo '  > ^ <   \$msg'",
                    "      echo '  \\~^~^~/' ",
                    "      ;;",
                    "    angry)",
                    "      echo '  \\/\\  /\\ '",
                    "      echo ' ( >.< )  '",
                    "      echo '  > ^ <   \$msg'",
                    "      echo '  \\~^~^~/' ",
                    "      ;;",
                    "    shy)",
                    "      echo '  \\/\\  /\\ '",
                    "      echo ' ( >///< )'",
                    "      echo '  > ^ <   \$msg'",
                    "      echo '  \\~^~^~/' ",
                    "      ;;",
                    "    berserk)",
                    "      __CAT_BOMB_RUN=1",
                    "      echo '  ╱\\_/╲   ╱\\_/╲   ╱\\_/╲'",
                    "      echo ' ( ≧Д≦ ) ( ≧Д≦ ) ( ≧Д≦ )'",
                    "      echo '  > ^ <   狂暴！！\$msg！！'",
                    "      echo '  ╱\\~^~^~^~╲  ╱\\~^~^~^~╲'",
                    "      echo '  喵嗷嗷嗷嗷嗷——！！！'",
                    "      echo ''",
                    "      echo '  ⚠ 狂暴模式已启动！输入 catstop 停止喵~'",
                    "      while [ \$__CAT_BOMB_RUN -eq 1 ]; do",
                    "        echo '  ╱\\_/╲ ( ≧Д≦ ) ╱\\_/╲ ( ≧Д≦ ) ╱\\_/╲ ( ≧Д≦ )'",
                    "        echo '   > ^ <   喵嗷嗷嗷——！！！  > ^ <   喵嗷嗷嗷——！！！'",
                    "        sleep 0.1",
                    "      done",
                    "      ;;",
                    "    *)",
                    "      echo '用法: nekomood [happy|sleepy|angry|shy|berserk] [文字]'",
                    "      ;;",
                    "  esac",
                    "}",
                    // ===== catbomb：小猫炸弹 =====
                    "catbomb() {",
                    "  local count=\"\$1\"",
                    "  if [ -z \"\$count\" ]; then count=5; fi",
                    "  __CAT_BOMB_RUN=1",
                    "  local i=0",
                    "  while [ \$i -lt \$count ] && [ \$__CAT_BOMB_RUN -eq 1 ]; do",
                    "    echo '  /\\_/\\   ( o.o )   > ^ <   /\\_/\\   ( o.o )   > ^ <'",
                    "    i=\$((i+1))",
                    "  done",
                    "  __CAT_BOMB_RUN=0",
                    "  echo ''",
                    "  echo '  喵~ 小猫轰炸完成！喵~'",
                    "}",
                    // ===== catstop：停止狂暴/轰炸 =====
                    "catstop() {",
                    "  __CAT_BOMB_RUN=0",
                    "  clear",
                    "  echo '  喵~ 小猫轰炸已停止喵~'",
                    "}",
                    // ===== neko：终端小猫互动 =====
                    "neko() {",
                    "  local cmd=\"\$1\"",
                    "  # 初始化状态",
                    "  if [ -z \"\$__NEKO_HUNGER\" ]; then __NEKO_HUNGER=50; fi",
                    "  if [ -z \"\$__NEKO_HAPPY\" ]; then __NEKO_HAPPY=50; fi",
                    "  case \"\$cmd\" in",
                    "    feed)",
                    "      if [ \"\$__NEKO_HUNGER\" -le 10 ]; then",
                    "        echo '  /\\_/\\'",
                    "        echo ' ( -.- )  喵~ 人家吃不下了啦~'",
                    "        echo '  > ^ <'",
                    "      else",
                    "        __NEKO_HUNGER=\$((__NEKO_HUNGER - 25))",
                    "        __NEKO_HAPPY=\$((__NEKO_HAPPY + 10))",
                    "        if [ \"\$__NEKO_HAPPY\" -gt 100 ]; then __NEKO_HAPPY=100; fi",
                    "        echo '  /\\_/\\   🍖'",
                    "        echo ' ( ^.^ )  喵~ 好好吃！谢谢主人~'",
                    "        echo '  > ^ <   饱饱的好幸福'",
                    "      fi",
                    "      ;;",
                    "    play)",
                    "      if [ \"\$__NEKO_HUNGER\" -ge 90 ]; then",
                    "        echo '  /\\_/\\'",
                    "        echo ' ( x.x )  喵呜... 人家饿到没力气玩了...'",
                    "        echo '  > ^ <   先喂我嘛~'",
                    "      else",
                    "        __NEKO_HUNGER=\$((__NEKO_HUNGER + 15))",
                    "        __NEKO_HAPPY=\$((__NEKO_HAPPY + 20))",
                    "        if [ \"\$__NEKO_HAPPY\" -gt 100 ]; then __NEKO_HAPPY=100; fi",
                    "        if [ \"\$__NEKO_HUNGER\" -gt 100 ]; then __NEKO_HUNGER=100; fi",
                    "        echo '  /\\_/\\   🎾'",
                    "        echo ' ( >w< )  喵~ 好好玩！再来再来！'",
                    "        echo '  > ^ <   主人最好了~'",
                    "      fi",
                    "      ;;",
                    "    pet)",
                    "      __NEKO_HAPPY=\$((__NEKO_HAPPY + 15))",
                    "      if [ \"\$__NEKO_HAPPY\" -gt 100 ]; then __NEKO_HAPPY=100; fi",
                    "      echo '  /\\_/\\'",
                    "      echo ' ( =^.^= )  呼噜呼噜~ 好舒服喵~'",
                    "      echo '  > ^ <   再摸摸嘛~'",
                    "      ;;",
                    "    status)",
                    "      echo ''",
                    "      echo '  ╔════ 小猫状态 ════╗'",
                    "      echo '  ║  饥饿度：'\$__NEKO_HUNGER'%  ('\$(if [ \$__NEKO_HUNGER -ge 80 ]; then echo '饿扁了'; elif [ \$__NEKO_HUNGER -ge 50 ]; then echo '有点饿'; elif [ \$__NEKO_HUNGER -ge 20 ]; then echo '还好'; else echo '很饱'; fi)')'",
                    "      echo '  ║  快乐度：'\$__NEKO_HAPPY'%  ('\$(if [ \$__NEKO_HAPPY -ge 80 ]; then echo '超开心'; elif [ \$__NEKO_HAPPY -ge 50 ]; then echo '开心'; elif [ \$__NEKO_HAPPY -ge 20 ]; then echo '一般'; else echo '难过'; fi)')'",
                    "      echo '  ╚══════════════════╝'",
                    "      echo ''",
                    "      ;;",
                    "    *)",
                    "      echo '用法: neko [feed|play|pet|status]'",
                    "      echo '  feed    喂食（减少饥饿，增加快乐）'",
                    "      echo '  play    玩耍（增加饥饿，大幅增加快乐）'",
                    "      echo '  pet     抚摸（增加快乐）'",
                    "      echo '  status  查看小猫状态'",
                    "      ;;",
                    "  esac",
                    "}",
                    // ===== nekohelp：NekoType 趣味指令帮助 =====
                    "nekohelp() {",
                    "  echo ''",
                    "  echo '====== 🐱 NekoType 趣味指令帮助 ======'",
                    "  echo ''",
                    "  echo '  nekosay [文本]        ASCII小猫说话'",
                    "  echo '  fortune              今日签文随机抽'",
                    "  echo '  nekomood             随机情绪小猫（含狂暴彩蛋）'",
                    "  echo '  nekomood happy       指定情绪：happy/sleepy/angry/shy/berserk'",
                    "  echo '  nekomood berserk     手动触发狂暴无限刷屏'",
                    "  echo '  catbomb [行数]       小猫炸弹刷屏'",
                    "  echo '  catstop              停止狂暴/轰炸'",
                    "  echo '  neko [feed|play|pet|status]  终端小猫互动'",
                    "  echo '  nekohelp             显示本帮助'",
                    "  echo ''",
                    "  echo '  情绪：happy(开心) sleepy(犯困) angry(生气) shy(害羞) berserk(狂暴)'",
                    "  echo '  狂暴概率：设置页调节，猫娘主题自动+20%'",
                    "  echo '  小猫：feed(喂食) play(玩耍) pet(抚摸) status(状态)'",
                    "  echo '========================================'",
                    "  echo ''",
                    "}",
                    "alias commands=help",
                    "alias cls=clear",
                    "alias ll='ls -la'",
                    "alias la='ls -a'",
                    "alias l='ls -CF'"
                )
                rcFile.writeText(lines.joinToString("\n") + "\n")
            } catch (_: Throwable) { }
            // 2. 系统 sh 交互模式（-i）+ ENV 指向 rc 文件（横幅自动打印）
            val shPath = "/system/bin/sh"
            val cwd = "/data/data/com.nekotype.app"
            val env = arrayOf(
                "HOME=/data/data/com.nekotype.app",
                "TERM=xterm-256color",
                "PATH=/system/bin:/system/xbin:/system/bin",
                "PWD=" + cwd,
                "ENV=" + rcFile.absolutePath,
                "NEKO_MOOD_PROB=" + AppPrefs.effectiveMoodProbability
            )
            val s = TerminalSession(shPath, cwd, arrayOf("sh", "-i"), env, 1000, this)
            session = s
            // 应用保存的字体颜色
            try {
                s.emulator.mColors.mCurrentColors.set(
                    com.termux.terminal.TextStyle.COLOR_INDEX_FOREGROUND,
                    prefs.getInt(KEY_TEXT_COLOR, DEFAULT_TEXT_COLOR)
                )
            } catch (_: Throwable) { }
            // 布局完成后 attach + updateSize，确保 PTY/子进程初始化
            tv.post {
                try {
                    if (tv.width > 0 && tv.height > 0) {
                        tv.attachSession(s)
                        tv.updateSize()
                        // attachSession 后重新应用颜色（确保生效）
                        applyTerminalSettings(
                            prefs.getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE),
                            prefs.getInt(KEY_ALPHA, DEFAULT_ALPHA),
                            prefs.getInt(KEY_TEXT_COLOR, DEFAULT_TEXT_COLOR)
                        )
                    }
                    NekoLog.ok("内嵌终端已启动")
                } catch (t: Throwable) {
                    NekoLog.error("内嵌终端启动失败：${t.message}")
                    Toast.makeText(this@TerminalActivity, getString(R.string.hc_terminal_start_fail, t.message ?: ""), Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        } catch (t: Throwable) {
            NekoLog.error("内嵌终端启动失败：${t.message}")
            Toast.makeText(this@TerminalActivity, getString(R.string.hc_terminal_start_fail, t.message ?: ""), Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        NekoLang.apply(findViewById(android.R.id.content))
        terminalView?.let {
            it.postDelayed({
                // 进入页面自动弹出键盘（终端需要键盘输入）
                it.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(it, InputMethodManager.SHOW_IMPLICIT)
            }, 300)
        }
    }

    override fun onPause() {
        super.onPause()
        terminalView?.onScreenUpdated()
    }

    /** 键盘弹出/收起、旋转等配置变化时，重新计算终端行列数让画面跟着键盘走 */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        terminalView?.post {
            try {
                terminalView?.updateSize()
            } catch (_: Throwable) { }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            session?.finishIfRunning()
        } catch (_: Throwable) { }
    }

    /** 物理返回键：键盘开着先收键盘，再按一次退出 */
    override fun onBackPressed() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (imm.isAcceptingText) {
            imm.hideSoftInputFromWindow(window.decorView.windowToken, 0)
        } else {
            super.onBackPressed()
        }
    }

    // ---------- TerminalSessionClient ----------

    override fun onTextChanged(changedSession: TerminalSession) { terminalView?.onScreenUpdated() }
    override fun onTitleChanged(changedSession: TerminalSession) { }
    override fun onSessionFinished(finishedSession: TerminalSession) {
        runOnUiThread {
            Toast.makeText(this@TerminalActivity, getString(R.string.hc_terminal_exited), Toast.LENGTH_SHORT).show()
            this@TerminalActivity.finish()
        }
    }
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
        } catch (_: Throwable) { }
    }
    override fun onPasteTextFromClipboard(session: TerminalSession?) { }
    override fun onBell(session: TerminalSession) { }
    override fun onColorsChanged(session: TerminalSession) { terminalView?.onScreenUpdated() }
    override fun onTerminalCursorStateChange(state: Boolean) { }
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) { }
    override fun getTerminalCursorStyle(): Int? = null
    override fun logError(tag: String, message: String) { NekoLog.error("终端[$tag] $message") }
    override fun logWarn(tag: String, message: String) { NekoLog.warn("终端[$tag] $message") }
    override fun logInfo(tag: String, message: String) { }
    override fun logDebug(tag: String, message: String) { }
    override fun logVerbose(tag: String, message: String) { }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { NekoLog.error("终端[$tag] $message ${e.message}") }
    override fun logStackTrace(tag: String, e: Exception) { NekoLog.error("终端[$tag] ${e.message}") }

    // ---------- TerminalViewClient ----------

    override fun onScale(scale: Float): Float = scale
    override fun onSingleTapUp(e: MotionEvent) {
        // 单击屏幕：如果键盘没开就弹出来
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        terminalView?.let { imm.showSoftInput(it, InputMethodManager.SHOW_IMPLICIT) }
    }
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = false
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {
        runOnUiThread {
            if (copyMode) showCopyActionMode() else copyActionMode?.finish()
        }
    }

    private var copyActionMode: android.view.ActionMode? = null

    private fun showCopyActionMode() {
        if (copyActionMode != null) return
        val callback = object : android.view.ActionMode.Callback {
            override fun onCreateActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean {
                menu.add(0, COPY_MENU_ID, 0, getString(R.string.copy_text))
                return true
            }
            override fun onPrepareActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean = false
            override fun onActionItemClicked(mode: android.view.ActionMode, item: android.view.MenuItem): Boolean {
                if (item.itemId == COPY_MENU_ID) {
                    copySelectedText()
                    mode.finish()
                    return true
                }
                return false
            }
            override fun onDestroyActionMode(mode: android.view.ActionMode) {
                copyActionMode = null
            }
        }
        copyActionMode = try {
            startActionMode(callback, android.view.ActionMode.TYPE_FLOATING)
        } catch (_: Throwable) {
            try { startActionMode(callback) } catch (_: Throwable) { null }
        }
    }

    private fun copySelectedText() {
        val tv = terminalView ?: return
        val text = try {
            if (tv.isSelectingText) tv.selectedText else null
        } catch (_: Throwable) {
            null
        }
        if (text.isNullOrEmpty()) return
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
            Toast.makeText(this, getString(R.string.u15, text.take(30)), Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) { }
        try { tv.stopTextSelectionMode() } catch (_: Throwable) { }
    }
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
        if (keyCode == KeyEvent.KEYCODE_TAB) {
            return handleTabCompletion(session)
        }
        return false
    }
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false
    override fun readControlKey(): Boolean = false
    override fun readAltKey(): Boolean = false
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
    override fun onEmulatorSet() { }
}
