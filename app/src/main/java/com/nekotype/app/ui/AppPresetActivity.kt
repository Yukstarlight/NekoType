package com.nekotype.app.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch
import com.nekotype.app.R
import com.nekotype.app.prefs.AppPrefs
import com.nekotype.app.util.BgUtils
import com.nekotype.app.util.NekoLog
import com.nekotype.app.util.ThemeHelper
import java.util.Locale

/**
 * 应用预设映射：为指定应用绑定预设，前台切到该应用时自动切换（开关控制）。
 * UI：映射列表（应用+预设+删除）+ 添加（应用选择器：名称/包名过滤，"写设备"即出设备管理器）。
 */
class AppPresetActivity : AppCompatActivity() {

    private lateinit var llMap: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_preset)
        BgUtils.apply(findViewById(R.id.root))
        NekoLog.nav("打开应用预设映射")

        findViewById<MaterialSwitch>(R.id.swMap).apply {
            isChecked = AppPrefs.appPresetAutoSwitch
            setOnCheckedChangeListener { _, v ->
                AppPrefs.appPresetAutoSwitch = v
                NekoLog.adjust(if (v) "开启应用预设映射自动切换" else "关闭应用预设映射自动切换")
            }
        }

        findViewById<TextView>(R.id.btnAdd).setOnClickListener { showAppPicker() }

        llMap = findViewById(R.id.llMap)
        renderMap()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun renderMap() {
        llMap.removeAllViews()
        val map = AppPrefs.appPresetMap()
        val presets = AppPrefs.presetList().toMap()
        findViewById<TextView>(R.id.tvEmpty).visibility = if (map.isEmpty()) View.VISIBLE else View.GONE
        map.forEach { (pkg, presetId) ->
            val label = try {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(pkg, 0)
                ).toString()
            } catch (_: Throwable) { pkg }
            val presetName = presets[presetId] ?: presetId

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = ContextCompat.getDrawable(this@AppPresetActivity, R.drawable.bg_bubble_in)
            }
            row.addView(TextView(this).apply {
                text = "$label\n$presetName"
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@AppPresetActivity, R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = getString(R.string.u79)
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@AppPresetActivity, R.color.md_theme_primary))
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(8), dp(8), dp(8))
                setOnClickListener { showPresetPicker(pkg, presetId) }
            })
            row.addView(TextView(this).apply {
                text = "✕"
                textSize = 16f
                setTextColor(ContextCompat.getColor(this@AppPresetActivity, R.color.fg_2))
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(8), dp(8), dp(8))
                setOnClickListener {
                    AppPrefs.removeAppPreset(pkg)
                    NekoLog.adjust("移除应用映射：$label")
                    toast(getString(R.string.ap_removed))
                    renderMap()
                }
            })
            llMap.addView(row)
        }
    }

    private var appCache: List<Pair<ApplicationInfo, String>>? = null

    /** 应用选择器：后台线程加载列表（主线程加载会卡死，表现为"点一次没反应"），NekoDialog 风格 */
    private fun showAppPicker() {
        val cached = appCache
        if (cached != null) {
            showPickerDialog(cached)
            return
        }
        toast(getString(R.string.hc_applist_loading))
        Thread {
            val pm = packageManager
            val list = mutableListOf<Pair<ApplicationInfo, String>>()
            try {
                pm.getInstalledApplications(PackageManager.MATCH_ALL).forEach { info ->
                    if (info.packageName == packageName) return@forEach
                    if (pm.getLaunchIntentForPackage(info.packageName) != null) {
                        val label = try { pm.getApplicationLabel(info).toString() } catch (_: Throwable) { info.packageName }
                        list.add(info to label)
                    }
                }
                list.sortBy { it.second.lowercase(Locale.getDefault()) }
            } catch (_: Throwable) { }
            appCache = list
            runOnUiThread { showPickerDialog(list) }
        }.start()
    }

    private fun showPickerDialog(apps: List<Pair<ApplicationInfo, String>>) {
        val pm = packageManager
        val search = NekoDialog.input(this, getString(R.string.i1))
        val listHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = NekoDialog.scroll(listHolder)
        // 黑名单同款加载动画：列表后台加载期间显示转圈，加载完切列表
        val loading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(24), dp(8), dp(24))
            addView(android.widget.ProgressBar(this@AppPresetActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
                isIndeterminate = true
            })
            addView(TextView(this@AppPresetActivity).apply {
                text = getString(R.string.hc_applist_loading)
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@AppPresetActivity, R.color.fg_2))
                setPadding(0, dp(10), 0, 0)
            })
        }
        val content = NekoDialog.column(this, search, loading, scroll)
        val dialog = NekoDialog.builder(this)
            .setTitle(getString(R.string.ap_pick_app))
            .setView(content)
            .setNegativeButton(getString(R.string.u72), null)
            .show()
        scroll.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(340)
        )
        scroll.visibility = View.GONE

        fun renderList(query: String) {
            listHolder.removeAllViews()
            val q = query.trim().lowercase(Locale.getDefault())
            apps.filter { (info, label) ->
                q.isEmpty() || label.lowercase(Locale.getDefault()).contains(q) ||
                        info.packageName.lowercase(Locale.getDefault()).contains(q)
            }.take(60).forEach { (info, label) ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                    isClickable = true
                    isFocusable = true
                }
                row.addView(ImageView(this).apply {
                    setImageDrawable(info.loadIcon(pm))
                    layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                })
                row.addView(TextView(this).apply {
                    text = "$label\n${info.packageName}"
                    textSize = 13f
                    setTextColor(ContextCompat.getColor(this@AppPresetActivity, R.color.text_primary))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp(10)
                    }
                })
                row.setOnClickListener {
                    dialog.dismiss()
                    showPresetPicker(info.packageName, AppPrefs.mappedPresetFor(info.packageName) ?: "")
                }
                listHolder.addView(row)
            }
        }
        loading.visibility = View.GONE
        scroll.visibility = View.VISIBLE
        renderList("")
        search.editText?.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { renderList(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    /** 预设选择器：点选预设写入映射；再次点同一预设 = 取消映射 */
    private fun showPresetPicker(pkg: String, currentPresetId: String) {
        val presets = AppPrefs.presetList()
        val items = presets.map { it.second + if (it.first == currentPresetId) " ✓" else "" }.toTypedArray()
        NekoDialog.builder(this)
            .setTitle(getString(R.string.ap_pick_preset))
            .setItems(items) { _, which ->
                val id = presets[which].first
                if (id == currentPresetId) {
                    AppPrefs.removeAppPreset(pkg)
                    NekoLog.adjust("移除应用映射：$pkg")
                } else {
                    AppPrefs.setAppPreset(pkg, id)
                    val name = presets.firstOrNull { it.first == id }?.second ?: id
                    NekoLog.adjust("应用映射：$pkg → $name")
                }
                renderMap()
            }
            .show()
    }


    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
