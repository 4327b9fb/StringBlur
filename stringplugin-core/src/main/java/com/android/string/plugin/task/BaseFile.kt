package com.android.string.plugin.task

import com.android.string.plugin.data.Constant
import com.android.string.plugin.mode.Mode
import com.palantir.javapoet.JavaFile
import com.palantir.javapoet.TypeSpec
import java.io.File

/**
 * @author chancey
 * @date   2023/9/5   18:58
 **/
abstract class BaseFile {

    abstract fun getImplClassName(): String

    fun create(baseDir: File, applicationId: String, mode: Mode) {
        create(baseDir, applicationId, listOf(mode))
    }

    open fun create(baseDir: File, applicationId: String, modes: List<Mode>) {
        val typeSpec = buildTypeSpec(applicationId, modes)
        val pkg = Constant.PLUGIN_CLASS_PACKAGE.format(applicationId)
        JavaFile.builder(pkg, typeSpec).build().writeTo(baseDir)
    }

    /**
     * 带 key 参数的创建方法，供需要 key 派生种子的模式使用（如 LONG_PRNG）。
     */
    open fun create(baseDir: File, applicationId: String, modes: List<Mode>, key: String) {
        create(baseDir, applicationId, modes)
    }

    abstract fun buildTypeSpec(applicationId: String, modes: List<Mode>): TypeSpec
}