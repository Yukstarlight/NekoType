package com.nekotype.app.module

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.ViewGroup
import com.nekotype.app.prefs.AppPrefs
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * 模块管理器：安装 / 列表 / 启停 / 卸载 / 导出 / 加载。
 *
 * 模块文件放在 `filesDir/modules/<id>/`（module.json + classes.dex + 资源），
 * 启用状态存在 AppPrefs.modulesJson。加载时用 DexClassLoader 以宿主 ClassLoader 为父加载器，
 * 模块因此可以直接使用宿主类与钩子。
 */
object ModuleManager {

    const val TAG = "NekoModule"

    private const val MAX_ENTRIES = 300
    private const val MAX_TOTAL_BYTES = 32L * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 64 * 1024
    private const val IMPORT_TMP = "_import.tmp"
    private const val ID_PATTERN = "[A-Za-z0-9._-]{1,64}"

    val KNOWN_ABILITIES = setOf(
        "text_transform", "settings_card", "splash", "activity", "menu", "hide_feature", "ui_override"
    )

    data class ModuleInfo(
        val id: String,
        val name: String,
        val version: String,
        val author: String,
        val description: String,
        val entryClass: String,
        val dexName: String,
        val minVersionCode: Long,
        val abilities: List<String>,
        val dir: File
    ) {
        fun dexFile(): File = File(dir, dexName)
    }

    private val loaded = mutableMapOf<String, NekoModule>()
    private val loaders = mutableMapOf<String, ClassLoader>()
    private val loadErrors = mutableMapOf<String, String>()

    fun modulesRoot(ctx: Context): File = File(ctx.filesDir, "modules").apply { mkdirs() }

    fun list(ctx: Context): List<ModuleInfo> = modulesRoot(ctx).listFiles().orEmpty()
        .filter { it.isDirectory && it.name != IMPORT_TMP }
        .mapNotNull { d ->
            try {
                val mf = File(d, "module.json")
                if (!mf.isFile) null else parseManifest(mf.readText(Charsets.UTF_8), d)
            } catch (_: Throwable) {
                null
            }
        }
        .sortedBy { it.name.lowercase() }

    /** 超预算立即中止的复制（防"声明很小、实际炸弹"的 ZIP） */
    private fun copyLimited(ins: java.io.InputStream, outs: java.io.OutputStream, budget: Long): Long {
        val buf = ByteArray(64 * 1024)
        var used = 0L
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            if (n == 0) continue
            used += n
            if (used > budget) throw IllegalStateException("模块解压后过大（> 32MB）")
            outs.write(buf, 0, n)
        }
        return used
    }

    private fun sha256Of(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun loadErrors(): Map<String, String> = loadErrors.toMap()

    fun isLoaded(id: String): Boolean = loaded.containsKey(id)

    private fun parseManifest(json: String, d: File): ModuleInfo {
        val o = JSONObject(json)
        val id = o.optString("id")
        require(id.matches(Regex(ID_PATTERN))) { "模块 id 不合法：$id" }
        val arr = o.optJSONArray("abilities")
        val abilities = if (arr == null) emptyList() else (0 until arr.length()).map { arr.optString(it) }
        return ModuleInfo(
            id = id,
            name = o.optString("name", id),
            version = o.optString("version", "?"),
            author = o.optString("author", ""),
            description = o.optString("description", ""),
            entryClass = o.optString("entryClass"),
            dexName = o.optString("dex", "classes.dex"),
            minVersionCode = o.optLong("minVersionCode", 0L),
            abilities = abilities,
            dir = d
        )
    }

    fun appVersionCode(ctx: Context): Long = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
    } catch (_: Throwable) {
        0L
    }

    private fun states(): JSONObject = try {
        val raw = AppPrefs.modulesJson
        if (raw.isBlank()) JSONObject() else JSONObject(raw)
    } catch (_: Throwable) {
        JSONObject()
    }

    private fun saveStates(o: JSONObject): Boolean = AppPrefs.saveModulesJsonSync(o.toString())

    fun isEnabled(id: String): Boolean = states().optBoolean(id, true)

    /** 返回是否写入成功；失败时界面要提示，否则开关会默默弹回 */
    fun setEnabled(ctx: Context, id: String, enabled: Boolean): Boolean {
        val ok = states().also { it.put(id, enabled) }.let { saveStates(it) }
        reload(ctx)
        return ok
    }

    fun installFromUri(ctx: Context, uri: Uri): Result<ModuleInfo> = try {
        val tmp = File(ctx.cacheDir, "nekotype-module-import.zip")
        val ok = ctx.contentResolver.openInputStream(uri)?.use { ins ->
            // 压缩包本身也不许无限大（按实际字节计，防占满缓存）
            tmp.outputStream().use { copyLimited(ins, it, MAX_TOTAL_BYTES + 8L * 1024 * 1024) }
            true
        } ?: false
        val r = if (ok) installFromFile(ctx, tmp) else Result.failure(IllegalStateException("无法读取所选文件"))
        tmp.delete()
        r
    } catch (t: Throwable) {
        runCatching { File(ctx.cacheDir, "nekotype-module-import.zip").delete() }
        Result.failure(t)
    }

    fun installFromFile(ctx: Context, zipFile: File): Result<ModuleInfo> = try {
        ZipFile(zipFile).use { zf ->
            val entries = zf.entries().toList()
            if (entries.size > MAX_ENTRIES) {
                return Result.failure(IllegalStateException("模块条目过多（> $MAX_ENTRIES）"))
            }
            var total = 0L
            for (e in entries) {
                val n = e.name
                if (n.contains("..") || n.startsWith("/") || n.contains('\\') || n.contains(":")) {
                    return Result.failure(IllegalStateException("模块含非法路径：$n"))
                }
                total += e.size
            }
            if (total > MAX_TOTAL_BYTES) {
                return Result.failure(IllegalStateException("模块解压后过大（> 32MB）"))
            }
            val mfEntry = entries.firstOrNull { it.name == "module.json" }
                ?: return Result.failure(IllegalStateException("缺少 module.json（须位于 ZIP 根目录）"))
            if (mfEntry.size > MAX_MANIFEST_BYTES) {
                return Result.failure(IllegalStateException("module.json 过大"))
            }
            // 按实际读到的字节限长，不信任 ZIP 中央目录声明的 size
            val mfBuf = java.io.ByteArrayOutputStream()
            zf.getInputStream(mfEntry).use { copyLimited(it, mfBuf, MAX_MANIFEST_BYTES.toLong()) }
            val manifestText = mfBuf.toString("UTF-8")
            val tmpDir = File(modulesRoot(ctx), "_import.tmp")
            tmpDir.deleteRecursively()
            tmpDir.mkdirs()
            val info = parseManifest(manifestText, tmpDir)
            if (info.entryClass.isBlank()) {
                return Result.failure(IllegalStateException("module.json 缺少 entryClass"))
            }
            if (entries.none { it.name == info.dexName }) {
                return Result.failure(IllegalStateException("缺少 ${info.dexName}"))
            }
            val hostVer = appVersionCode(ctx)
            if (info.minVersionCode > 0 && hostVer > 0 && info.minVersionCode > hostVer) {
                return Result.failure(IllegalStateException("模块要求应用版本 ≥ ${info.minVersionCode}（当前 $hostVer）"))
            }
            var actual = 0L
            for (e in entries) {
                if (e.isDirectory) continue
                val out = File(tmpDir, e.name)
                out.parentFile?.mkdirs()
                zf.getInputStream(e).use { ins ->
                    out.outputStream().use { os ->
                        actual += copyLimited(ins, os, MAX_TOTAL_BYTES - actual)
                    }
                }
            }
            val dest = File(modulesRoot(ctx), info.id)
            dest.deleteRecursively()
            if (!tmpDir.renameTo(dest)) {
                tmpDir.copyRecursively(dest, overwrite = true)
                tmpDir.deleteRecursively()
            }
            // 记录 DEX 哈希：之后每次加载都比对，模块文件被替换/篡改则不加载
            try {
                val dexHash = sha256Of(File(dest, info.dexName))
                val hashes = JSONObject(AppPrefs.moduleHashesJson)
                hashes.put(info.id, dexHash)
                AppPrefs.moduleHashesJson = hashes.toString()
            } catch (t: Throwable) {
                Log.w(TAG, "记录模块哈希失败（下次加载会补记）", t)
            }
            states().also { it.put(info.id, true) }.let { saveStates(it) }
            Log.i(TAG, "已安装模块 ${info.id} ${info.name} ${info.version}")
            reload(ctx)
            Result.success(info)
        }
    } catch (t: Throwable) {
        // 失败不留残骸：临时目录里的 module.json/classes.dex 不能被下次启动误加载
        runCatching { File(modulesRoot(ctx), IMPORT_TMP).deleteRecursively() }
        Result.failure(t)
    }

    fun uninstall(ctx: Context, id: String): Boolean = try {
        unloadOne(id)
        File(modulesRoot(ctx), id).deleteRecursively()
        val o = states()
        o.remove(id)
        saveStates(o)
        reload(ctx)
        true
    } catch (_: Throwable) {
        false
    }

    @Synchronized
    fun loadAll(ctx: Context) {
        loaded.keys.toList().forEach { unloadOne(it) }
        ModuleHooks.clearAll()
        loadErrors.clear()
        for (m in list(ctx)) {
            if (!isEnabled(m.id)) continue
            try {
                val dex = m.dexFile()
                if (!dex.isFile) throw IllegalStateException("缺少 ${m.dexName}")
                val actualHash = sha256Of(dex)
                val hashes = JSONObject(AppPrefs.moduleHashesJson)
                val expected = hashes.optString(m.id, "")
                if (expected.isEmpty()) {
                    // 旧版本安装的模块：首次加载补记哈希
                    hashes.put(m.id, actualHash)
                    AppPrefs.moduleHashesJson = hashes.toString()
                } else if (expected != actualHash) {
                    throw IllegalStateException("模块 DEX 与安装时不一致，已拒绝加载")
                }
                val optDir = File(ctx.codeCacheDir, "mod-${m.id}").apply { mkdirs() }
                val cl = dalvik.system.DexClassLoader(dex.absolutePath, optDir.absolutePath, null, ctx.classLoader)
                val obj = cl.loadClass(m.entryClass).getDeclaredConstructor().newInstance()
                if (obj !is NekoModule) throw IllegalStateException("入口类未实现 NekoModule")
                obj.onLoad(HostImpl(ctx, m.id))
                loaded[m.id] = obj
                loaders[m.id] = cl
                Log.i(TAG, "模块已加载：${m.id}（${m.name}）")
            } catch (t: Throwable) {
                loadErrors[m.id] = "${t.javaClass.simpleName}: ${t.message}"
                Log.w(TAG, "模块加载失败：${m.id}", t)
            }
        }
    }

    @Synchronized
    fun reload(ctx: Context) {
        loadAll(ctx)
    }

    private fun unloadOne(id: String) {
        try {
            loaded.remove(id)?.onUnload()
        } catch (_: Throwable) {
        }
        ModuleHooks.clearModule(id)
        loaders.remove(id)
        loadErrors.remove(id)
    }

    private class HostImpl(private val ctx: Context, override val moduleId: String) : ModuleHost {
        override val context: Context get() = ctx

        override val prefs: SharedPreferences by lazy {
            ctx.getSharedPreferences("nekotype_module_$moduleId", Context.MODE_PRIVATE)
        }

        override fun log(msg: String) {
            Log.i(TAG, "[$moduleId] $msg")
        }

        override fun addTextTransformer(f: (String, String) -> String?) =
            ModuleHooks.addTextTransformer(moduleId, f)

        override fun addSettingsCard(title: String, builder: (Context) -> View) =
            ModuleHooks.addSettingsCard(moduleId, title, builder)

        override fun addSplashDecorator(builder: (Activity, ViewGroup) -> Unit) =
            ModuleHooks.addSplashDecorator(moduleId, builder)

        override fun addActivityHook(f: (Activity) -> Unit) =
            ModuleHooks.addActivityHook(moduleId, f)

        override fun addMenuItem(label: String, onClick: () -> Unit) =
            ModuleHooks.addMenuItem(moduleId, label, onClick)

        override fun hideFeature(key: String) {
            // 模块不允许隐藏自己的管理入口，否则恶意模块可让自己无法被卸载
            if (key.equals(FeatureKeys.MODULES, ignoreCase = true)) {
                Log.w(TAG, "模块 $moduleId 试图隐藏模块管理入口，已忽略")
                return
            }
            ModuleHooks.hideFeature(key)
        }

        override fun splashDelay(ms: Long) = ModuleHooks.splashDelay(ms)
    }
}
