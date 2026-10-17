package com.nekotype.app.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.nekotype.app.R
import com.nekotype.app.module.ModuleManager
import com.nekotype.app.module.ModuleUi
import java.io.File

/** 模块管理：安装 / 启停 / 删除 / 导出，以及模块格式说明 */
class ModuleActivity : AppCompatActivity() {

    private lateinit var listBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.mod_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.mod_subtitle)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            alpha = 0.7f
            setPadding(0, dp(2), 0, dp(8))
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        row.addView(btn(getString(R.string.mod_import_zip)) { pickImport() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)

        root.addView(btn(getString(R.string.mod_doc)) {
            NekoDialog.showDoc(this, getString(R.string.mod_doc), getString(R.string.mod_doc_body))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        root.addView(TextView(this).apply {
            text = getString(R.string.mod_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(10), 0, 0)
        })

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox)

        setContentView(ScrollView(this).apply { addView(root) })

        intent?.getStringExtra("install_path")?.let { path ->
            try {
                val f = File(path)
                if (f.isFile) {
                    val r = ModuleManager.installFromFile(this, f)
                    toast(
                        r.fold(
                            { getString(R.string.mod_install_ok, it.name + " " + it.version) },
                            { getString(R.string.mod_fail, it.message ?: "?") }
                        )
                    )
                }
            } catch (_: Throwable) {
            }
        }
        when (intent?.getStringExtra("action")) {
            "import" -> root.post { pickImport() }
        }
        refresh()
    }

    private fun refresh() {
        listBox.removeAllViews()
        val mods = ModuleManager.list(this)
        val errors = ModuleManager.loadErrors()
        if (mods.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = getString(R.string.mod_empty)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(14), 0, 0)
            })
            return
        }
        for (m in mods) {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            box.addView(TextView(this).apply {
                text = "${m.name}  ${m.version}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, Typeface.BOLD)
            })
            if (m.author.isNotBlank() || m.description.isNotBlank()) {
                box.addView(TextView(this).apply {
                    text = listOf(m.author, m.description).filter { it.isNotBlank() }.joinToString(" · ")
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                })
            }
            if (m.abilities.isNotEmpty()) {
                box.addView(TextView(this).apply {
                    text = getString(R.string.mod_abilities, m.abilities.joinToString(", "))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                })
            }
            val enabled = ModuleManager.isEnabled(m.id)
            val status = when {
                !enabled -> getString(R.string.mod_status_disabled)
                errors.containsKey(m.id) -> getString(R.string.mod_status_error, errors[m.id] ?: "")
                ModuleManager.isLoaded(m.id) -> getString(R.string.mod_status_loaded)
                else -> getString(R.string.mod_status_disabled)
            }
            box.addView(TextView(this).apply {
                text = status
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            })

            val actionRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            actionRow.addView(MaterialSwitch(this).apply {
                text = getString(R.string.mod_enable)
                isChecked = enabled
                setOnCheckedChangeListener { _, v ->
                    ModuleManager.setEnabled(this@ModuleActivity, m.id, v)
                    refresh()
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            actionRow.addView(btn(getString(R.string.mod_delete)) { confirmDelete(m.id, m.name) })
            box.addView(actionRow)

            listBox.addView(ModuleUi.buildCard(this, "${m.id}", box))
        }
        listBox.addView(TextView(this).apply {
            text = getString(R.string.mod_restart_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(12), 0, 0)
        })
    }

    private fun confirmDelete(id: String, name: String) {
        NekoDialog.builder(this)
            .setTitle(getString(R.string.mod_delete))
            .setMessage(getString(R.string.mod_delete_confirm, name))
            .setPositiveButton(getString(R.string.u156)) { _, _ ->
                ModuleManager.uninstall(this, id)
                toast(getString(R.string.mod_deleted))
                refresh()
            }
            .setNegativeButton(getString(R.string.u72), null)
            .show()
    }

    private fun pickImport() {
        try {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*")
                    .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream")),
                3001
            )
        } catch (t: Throwable) {
            toast(getString(R.string.mod_fail, t.javaClass.simpleName))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            3001 -> {
                val r = ModuleManager.installFromUri(this, uri)
                val msg = r.fold(
                    { getString(R.string.mod_install_ok, it.name + " " + it.version) },
                    { getString(R.string.mod_fail, it.message ?: "?") }
                )
                NekoDialog.builder(this)
                    .setTitle(getString(R.string.mod_import_zip))
                    .setMessage(msg)
                    .setPositiveButton(getString(R.string.u156), null)
                    .show()
                refresh()
            }
        }
    }

    private fun btn(label: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            text = label
            minHeight = dp(44)
            setOnClickListener { onClick() }
        }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
