package com.nekotype.app.util

import android.util.TypedValue
import android.widget.TextView

/**
 * 按 `@dimen/ts_XX` 设置字号。
 *
 * 布局里的字号已统一写成 `android:textSize="@dimen/ts_14"`；
 * 代码里动态创建的文字（对话框、列表行、终端提示等）改用本方法，走同一套字号资源，
 * 这样 [com.nekotype.app.R.dimen] 的按语言覆盖（如俄语 values-ru 整体调小）才会在动态文字上同样生效。
 *
 * 注意：dimen 用 sp 定义，`getDimension` 已按 density 与 fontScale 换算成 px，
 * 因此这里用 `COMPLEX_UNIT_PX` 直接套用，避免二次缩放。
 */
fun TextView.setTextSizeDimen(resId: Int) {
    try {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(resId))
    } catch (_: Throwable) {
        // 资源缺失等异常不应影响 UI 构建
    }
}
