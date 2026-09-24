package com.lunashare.app.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 状态栏外观的「页面覆盖」。
 *
 * 背景：状态栏由 [LunaShareTheme] 统一刷成主题色（`colorScheme.surface` + 图标明暗），
 * 而且是在 `SideEffect` 里。但 Link Tab 打开 DSH 页面时，状态栏应该跟着**页面**走。
 *
 * ⚠️ 真机实测（Android 16 / 华为 SER-AN00）：本 App **不是** edge-to-edge，状态栏是 App 自己
 * 用 `window.statusBarColor` 实绘的一条不透明色带（截图像素：0..状态栏高 全是主题 surface
 * 色 `(18,19,24)`，下一行才是页面白）。所以「App 深色主题 + 页面浅色」时，状态栏就是一条
 * 黑边——这正是用户报的「状态栏还是黑色」。页面底色透不上来，只能主动染。
 *
 * 如果两边各写各的，谁后执行谁生效，就会出现随机闪烁。所以这里做一个共享落点：
 * 页面侧把想要的覆盖值写进来，[LunaShareTheme] 每次刷状态栏时优先读它、没有才用主题值。
 *
 * ⚠️ 字段必须是 **Compose 状态**（`mutableStateOf`），不能是普通字段：
 * [LunaShareTheme] 是在组合期读这两个值、在 `SideEffect` 里写进窗口。若用普通字段，
 * 页面侧改值既不会让 Theme 重组、也就不会重跑 SideEffect，状态栏会一直停在旧颜色上
 * （实测表现：从 Link 页切到 DSH 页后状态栏始终是 App 主题色，改再多遍都没反应）。
 *
 * 约定：`null` = 不覆盖（交回主题）。离开 DSH 页面 / 离开 Link Tab 时必须清空。
 */
object StatusBarOverride {

    /** 覆盖用的状态栏底色（ARGB）；null = 用主题色 */
    var color by mutableStateOf<Int?>(null)
        private set

    /**
     * 覆盖用的「状态栏是否为浅色底」，直接对应 `isAppearanceLightStatusBars`
     * （`true` = 浅色底 + **深色**图标；`false` = 深色底 + 浅色图标）。
     *
     * 命名按系统语义走，别按「图标明暗」理解——反了就会得到相反的图标颜色。
     * null = 用主题的明暗判断。
     */
    var lightStatusBar by mutableStateOf<Boolean?>(null)
        private set

    /** 一次性写入：深色页面 → 浅色图标。 */
    fun apply(color: Int?, darkPage: Boolean?) {
        this.color = color
        this.lightStatusBar = darkPage?.let { !it }
    }

    /** 清空覆盖，交回主题控制 */
    fun clear() {
        color = null
        lightStatusBar = null
    }
}
