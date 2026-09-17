package com.nekotype.app.util

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.nekotype.app.R
import com.nekotype.app.prefs.AppPrefs

/**
 * 猫娘用语工具：开启猫娘模式后，将UI文字替换为猫娘语气。
 * 在每个页面 onResume 中调用 apply(rootView) 即可。
 */
object NekoLang {

    /** 普通文本 → 猫娘文本的映射表 */
    private val langMap = mapOf(
        "设置" to "设置喵~",
        "首页" to "首页喵~",
        "规则" to "规则喵~",
        "模式" to "模式喵~",
        "终端" to "终端喵~",
        "集成终端" to "集成终端喵~",
        "启动服务" to "启动服务喵~",
        "停止服务" to "停止服务喵~",
        "已停止" to "已停止喵~",
        "运行中" to "运行中喵~",
        "服务已启动" to "服务已启动喵~",
        "服务已停止" to "服务已停止喵~",
        "权限引导" to "权限引导喵~",
        "权限状态" to "权限状态喵~",
        "系统能力" to "系统能力喵~",
        "去开启" to "去开启喵~",
        "去激活" to "去激活喵~",
        "去免电" to "去免电喵~",
        "去授权" to "去授权喵~",
        "请求" to "请求喵~",
        "未开启" to "未开启喵~",
        "已激活" to "已激活喵~",
        "已免电" to "已免电喵~",
        "已授权" to "已授权喵~",
        "悬浮窗" to "悬浮窗喵~",
        "无障碍" to "无障碍喵~",
        "免电" to "免电喵~",
        "Root" to "Root喵~",
        "管理员" to "管理员喵~",
        "运行模式" to "运行模式喵~",
        "当前模式" to "当前模式喵~",
        "悬浮球模式" to "悬浮球模式喵~",
        "篡改键盘模式" to "篡改键盘模式喵~",
        "断句追加模式" to "断句追加模式喵~",
        "外观" to "外观喵~",
        "悬浮按钮" to "悬浮按钮喵~",
        "高级" to "高级喵~",
        "数据" to "数据喵~",
        "黑名单" to "黑名单喵~",
        "详细信息" to "详细信息喵~",
        "赞助" to "赞助喵~",
        "关于" to "关于喵~",
        "隐私政策" to "隐私政策喵~",
        "语言" to "语言喵~",
        "关闭" to "关闭喵~",
        "保存" to "保存喵~",
        "取消" to "取消喵~",
        "确定" to "确定喵~",
        "删除" to "删除喵~",
        "编辑" to "编辑喵~",
        "添加" to "添加喵~",
        "导入" to "导入喵~",
        "导出" to "导出喵~",
        "分享" to "分享喵~",
        "复制" to "复制喵~",
        "清空" to "清空喵~",
        "重置" to "重置喵~",
        "默认" to "默认喵~",
        "浅色" to "浅色喵~",
        "深色" to "深色喵~",
        "星空" to "星空喵~",
        "自定义背景" to "自定义背景喵~",
        "清除背景" to "清除背景喵~",
        "开机自启" to "开机自启喵~",
        "隐藏模式" to "隐藏模式喵~",
        "密码锁定" to "密码锁定喵~",
        "静默修改" to "静默修改喵~",
        "心跳保活" to "心跳保活喵~",
        "日志" to "日志喵~",
        "反馈" to "反馈喵~",
        "版本" to "版本喵~",
        "累计" to "累计喵~",
        "今日" to "今日喵~",
        "欢迎使用" to "欢迎使用喵~",
        "首次使用" to "首次使用喵~",
        "全部完成后" to "全部完成后喵~",
        "按顺序完成" to "按顺序完成喵~",
        "核心权限已就绪" to "核心权限已就绪喵~",
        "一键免电" to "一键免电喵~",
        "快捷命令" to "快捷命令喵~",
        "收起快捷命令" to "收起快捷命令喵~",
        "终端设置" to "终端设置喵~",
        "字体大小" to "字体大小喵~",
        "面板透明度" to "面板透明度喵~",
        "人设语气包" to "人设语气包喵~",
        "萝莉语" to "萝莉语喵~",
        "古风文言" to "古风文言喵~",
        "译制片腔" to "译制片腔喵~",
        "阴阳怪气" to "阴阳怪气喵~",
        "火星文" to "火星文喵~",
        "病娇" to "病娇喵~",
        "雌小鬼" to "雌小鬼喵~",
        "猫娘" to "猫娘喵~",
        "已切换到" to "已切换到喵~",
        "已创建并切换到" to "已创建并切换到喵~",
        "设置已保存" to "设置已保存喵~",
        "配置导入成功" to "配置导入成功喵~",
        "自定义背景已应用" to "自定义背景已应用喵~"
    )

    /** 是否启用猫娘用语 */
    val enabled: Boolean get() = AppPrefs.nekoMode

    private const val TAG_NEKO_APPLIED = "neko_lang_applied"

    /**
     * 递归遍历 View 树，将匹配的 TextView 文字替换为猫娘用语。
     * 只替换完全匹配的文本，避免破坏动态内容。
     * 带 try-catch 保护，防止特殊控件导致崩溃。
     * 用 View.setTag 标记已处理的根 View，避免重复遍历。
     */
    fun apply(root: View?) {
        if (!enabled || root == null) return
        // 已处理过且状态未变则跳过
        if (root.getTag(R.id.tag_neko_applied) == true) return
        try {
            root.setTag(R.id.tag_neko_applied, true)
            val textViews = mutableListOf<TextView>()
            collectTextViews(root, textViews)
            textViews.forEach { tv ->
                try {
                    val text = tv.text?.toString() ?: return@forEach
                    val replaced = langMap[text]
                    if (replaced != null) {
                        tv.text = replaced
                    }
                } catch (_: Throwable) { }
            }
        } catch (_: Throwable) { }
    }

    private fun collectTextViews(view: View, out: MutableList<TextView>) {
        try {
            if (view is TextView) {
                out.add(view)
            } else if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    val child = view.getChildAt(i) ?: continue
                    collectTextViews(child, out)
                }
            }
        } catch (_: Throwable) { }
    }

    /** 单条文本转换 */
    fun convert(text: String): String {
        return if (enabled) langMap[text] ?: text else text
    }
}
