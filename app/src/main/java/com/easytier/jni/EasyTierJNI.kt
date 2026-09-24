package com.easytier.jni

/**
 * EasyTier JNI 接口（对应 libeasytier_android_jni.so，官方 easytier-android-jni 模块绑定）。
 *
 * Rust 侧导出符号前缀为 Java_com_easytier_jni_EasyTierJNI_*，
 * JNI 按声明类的完整限定名查找，因此本类**必须**保持在 com.easytier.jni
 * 包名下（与官方包一致），不可改名/移包，否则运行时 UnsatisfiedLinkError。
 */
object EasyTierJNI {

    init {
        // so 为自包含构建：easytier-ffi（no_mangle C 符号实现）已静态链入
        // libeasytier_android_jni.so（Android System.loadLibrary 为 RTLD_LOCAL，
        // 跨 so 符号解析不可靠，故不能拆两个库）
        System.loadLibrary("easytier_android_jni")
    }

    /** 设置 TUN 文件描述符。0 成功，-1 失败（失败时抛 RuntimeException）。 */
    @JvmStatic
    external fun setTunFd(instanceName: String, fd: Int): Int

    /** 解析 TOML 配置。0 成功，-1 失败。 */
    @JvmStatic
    external fun parseConfig(config: String): Int

    /** 运行网络实例（no_tun=true 时挂起等待 setTunFd 注入）。0 成功，-1 失败。 */
    @JvmStatic
    external fun runNetworkInstance(config: String): Int

    /** 仅保留指定实例，停止其它。null/空数组 = 停止全部。 */
    @JvmStatic
    external fun retainNetworkInstance(instanceNames: Array<String>?): Int

    /** 收集运行信息（JSON 字符串，形如 {"<inst_name>": {...}}）。 */
    @JvmStatic
    external fun collectNetworkInfos(maxLength: Int): String?

    /** 最后一条错误消息（无则 null）。 */
    @JvmStatic
    external fun getLastError(): String?

    /** 停止所有实例。 */
    @JvmStatic
    fun stopAllInstances(): Int = retainNetworkInstance(null)

    /** 仅保留单个实例。 */
    @JvmStatic
    fun retainSingleInstance(instanceName: String): Int =
        retainNetworkInstance(arrayOf(instanceName))
}
