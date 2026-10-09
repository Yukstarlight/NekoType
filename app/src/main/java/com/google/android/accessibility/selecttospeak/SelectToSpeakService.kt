package com.google.android.accessibility.selecttospeak

import com.nekotype.app.accessibility.NekoTypeAccessibilityService

/**
 * 服务组件名伪装：微信对无障碍服务按类名放行控件树，
 * 系统服务样式的类名（随选朗读）不会被屏蔽（NyaNote/GKD 同款做法）。
 */
class SelectToSpeakService : NekoTypeAccessibilityService()
