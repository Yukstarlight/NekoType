/*
 * NekoType
 *
 * BSD 2-Clause License
 *
 * Copyright (c) 2026, Yukstarlight
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.nekotype.app.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import com.nekotype.app.R
import com.nekotype.app.prefs.AppPrefs
import com.nekotype.app.util.BgUtils
import com.nekotype.app.util.NekoLog
import java.util.Locale

/**
 * 应用黑名单：勾选的应用内不自动篡改（强制篡改键盘 / 静默修改自动注入不生效）。
 * 悬浮球手动点击不受限制。
 */
class BlacklistActivity : AppCompatActivity() {

    private lateinit var adapter: AppAdapter
    private val apps = mutableListOf<Pair<ApplicationInfo, String>>() // appInfo + 应用名

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blacklist)
        BgUtils.apply(findViewById(R.id.root))
        NekoLog.nav("打开应用黑名单")

        // 总开关
        findViewById<MaterialSwitch>(R.id.swBlacklist).apply {
            isChecked = AppPrefs.blacklistEnabled
            setOnCheckedChangeListener { _, v ->
                AppPrefs.blacklistEnabled = v
                NekoLog.adjust(if (v) "启用应用黑名单" else "停用应用黑名单")
                updateHint()
            }
        }
        updateHint()

        // 全选 / 清空
        findViewById<TextView>(R.id.btnSelectAll).setOnClickListener {
            apps.forEach { AppPrefs.addBlacklist(it.first.packageName) }
            adapter.notifyDataSetChanged()
            toast("已全选：${apps.size} 个应用加入黑名单")
        }
        findViewById<TextView>(R.id.btnClearAll).setOnClickListener {
            apps.forEach { AppPrefs.removeBlacklist(it.first.packageName) }
            adapter.notifyDataSetChanged()
            toast("已清空黑名单")
        }

        // 应用列表
        adapter = AppAdapter()
        findViewById<RecyclerView>(R.id.rvApps).apply {
            layoutManager = LinearLayoutManager(this@BlacklistActivity)
            adapter = this@BlacklistActivity.adapter
        }

        // 搜索（手动过滤，避免 Filter 类型推断问题）
        findViewById<EditText>(R.id.etSearch).addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { adapter.filter(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        loadApps()
    }

    private fun updateHint() {
        val cnt = AppPrefs.blacklist().size
        findViewById<TextView>(R.id.tvBlacklistHint).text =
            if (AppPrefs.blacklistEnabled) "已启用：黑名单内 $cnt 个应用不自动篡改（悬浮球手动不受限）"
            else "未启用：所有应用都自动篡改（可先开启开关）"
    }

    private fun loadApps() {
        val pm = packageManager
        val self = packageName
        val installed = pm.getInstalledApplications(PackageManager.MATCH_ALL)
        apps.clear()
        installed.forEach { info ->
            if (info.packageName == self) return@forEach
            // 只列有桌面入口的应用（更干净）
            if (pm.getLaunchIntentForPackage(info.packageName) != null) {
                val label = try { pm.getApplicationLabel(info).toString() } catch (_: Throwable) { info.packageName }
                apps.add(info to label)
            }
        }
        apps.sortBy { it.second.lowercase(Locale.getDefault()) }
        adapter.notifyDataSetChanged()
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    // ---------- 适配器 ----------

    inner class AppAdapter : RecyclerView.Adapter<AppAdapter.VH>() {

        /** null = 不过滤（显示全部）；非 null = 搜索结果 */
        private var shown: List<Pair<ApplicationInfo, String>>? = null

        private fun display(): List<Pair<ApplicationInfo, String>> = shown ?: apps

        /** 按应用名/包名过滤（空串恢复全量） */
        fun filter(q: String) {
            val query = q.trim().lowercase(Locale.getDefault())
            shown = if (query.isEmpty()) {
                null
            } else {
                val out = ArrayList<Pair<ApplicationInfo, String>>()
                for (p in apps) {
                    if (p.second.lowercase(Locale.getDefault()).contains(query) ||
                        p.first.packageName.lowercase(Locale.getDefault()).contains(query)
                    ) {
                        out.add(p)
                    }
                }
                out
            }
            notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.ivAppIcon)
            val name: TextView = v.findViewById(R.id.tvAppName)
            val pkg: TextView = v.findViewById(R.id.tvAppPkg)
            val check: CheckBox = v.findViewById(R.id.cbApp)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = layoutInflater.inflate(R.layout.item_blacklist_app, parent, false)
            return VH(v)
        }

        override fun getItemCount() = display().size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (info, label) = display()[position]
            val inBlacklist = AppPrefs.blacklist().contains(info.packageName)
            holder.icon.setImageDrawable(info.loadIcon(packageManager))
            holder.name.text = label
            holder.pkg.text = info.packageName
            holder.check.isChecked = inBlacklist
            // 勾选行背景高亮（勾选状态一目了然，弥补勾选符号显示问题）
            holder.itemView.setBackgroundColor(
                if (inBlacklist) {
                    androidx.core.content.ContextCompat.getColor(this@BlacklistActivity, R.color.bubble_in)
                } else {
                    android.graphics.Color.TRANSPARENT
                }
            )
            holder.itemView.setOnClickListener { toggle(holder, info.packageName) }
            holder.check.setOnClickListener { toggle(holder, info.packageName) }
        }

        private fun toggle(holder: VH, pkg: String) {
            val checked = !AppPrefs.blacklist().contains(pkg)
            if (checked) AppPrefs.addBlacklist(pkg) else AppPrefs.removeBlacklist(pkg)
            holder.check.isChecked = checked
            holder.itemView.setBackgroundColor(
                if (checked) {
                    androidx.core.content.ContextCompat.getColor(this@BlacklistActivity, R.color.bubble_in)
                } else {
                    android.graphics.Color.TRANSPARENT
                }
            )
            updateHint()
        }
    }
}
