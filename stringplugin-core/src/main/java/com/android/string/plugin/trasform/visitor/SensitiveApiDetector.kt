package com.android.string.plugin.trasform.visitor

/**
 * 敏感 API 识别：字符串直接流入这些调用时跳过加密、保持明文，
 * 避免（如）单元测试、热修等缺失解密链路的环境在反射/动态加载处崩溃，
 * 同时保证这些字符串在崩溃日志、hook 工具中的可读性。
 *
 * 识别为单槽启发式：仅当字符串是调用前最后压入的操作数时命中。
 *
 * @author chancey
 * @date   2026/8/30
 **/
object SensitiveApiDetector {

    private val sensitiveMethods = mapOf(
        // 反射与类加载
        "java/lang/Class" to setOf(
            "forName", "getMethod", "getDeclaredMethod", "getDeclaredField", "getField",
            "getConstructor", "getDeclaredConstructor", "getResource", "getResourceAsStream"
        ),
        "java/lang/ClassLoader" to setOf(
            "loadClass", "findClass", "findLibrary", "findResource", "getResource", "getResourceAsStream"
        ),
        "java/lang/invoke/MethodHandles\$Lookup" to setOf(
            "findStatic", "findVirtual", "findConstructor", "findSpecial",
            "findGetter", "findSetter", "findStaticGetter", "findStaticSetter"
        ),
        // native 库加载
        "java/lang/System" to setOf("load", "loadLibrary"),
        "java/lang/Runtime" to setOf("load", "loadLibrary"),
        // 组件与包信息
        "android/content/Intent" to setOf("setClassName", "setPackage"),
        "android/content/pm/PackageManager" to setOf(
            "getPackageInfo", "getApplicationInfo", "getActivityInfo",
            "getServiceInfo", "getProviderInfo", "getReceiverInfo"
        )
    )

    /**
     * owner/name 为 ASM 内部名，如 ("java/lang/Class", "forName")。
     */
    fun isSensitive(owner: String?, name: String?): Boolean {
        if (owner == null || name == null) {
            return false
        }
        return sensitiveMethods[owner]?.contains(name) == true
    }
}
