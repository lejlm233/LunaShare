package com.lunashare.app

import android.content.pm.ActivityInfo
import android.os.Bundle
import com.journeyapps.barcodescanner.CaptureActivity

/**
 * 竖屏锁定的扫码页（参考 HermesMobile 的 PortraitCaptureActivity）。
 *
 * 直接使用默认 CaptureActivity 时扫码框会跟随屏幕方向旋转，在手机上显示不正常；
 * 固定竖屏后扫码框稳定显示。
 */
class PortraitCaptureActivity : CaptureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
}
