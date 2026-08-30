package com.android.string.plugin.task.build

import com.android.string.plugin.data.Constant
import com.android.string.plugin.mode.Mode
import com.android.string.plugin.task.BaseFile
import com.android.string.plugin.util.ModeUtils
import com.squareup.javawriter.JavaWriter
import javax.lang.model.element.Modifier

/**
 * 生成解密调用类，内容见[com.android.string.plugin.demo_files.StringBlur]。
 * 类名与解密方法名由[com.android.string.plugin.util.EntryNames]按配置派生，
 * 不再使用固定的 StringBlur/decrypt。
 * @author chancey
 * @date   2023/9/5   17:24
 **/
class StringBlurFile : BaseFile() {

    fun create(path: java.io.File, applicationId: String, modes: List<Mode>, className: String, methodName: String) {
        val file = java.io.File(path, "$className.java")
        JavaWriter(java.io.FileWriter(file)).use {
            write(it, applicationId, modes, className, methodName)
        }
    }

    override fun create(path: java.io.File, applicationId: String, modes: List<Mode>) {
        create(path, applicationId, modes, Constant.PLUGIN_CLASS_NAME, "decrypt")
    }

    override fun write(writer: JavaWriter, applicationId: String, mode: Mode) {
        write(writer, applicationId, listOf(mode), Constant.PLUGIN_CLASS_NAME, "decrypt")
    }

    private fun write(writer: JavaWriter, applicationId: String, modes: List<Mode>, className: String, methodName: String) {
        val pkg = Constant.PLUGIN_CLASS_PACKAGE.format(applicationId)
        val imports = modes.map { ModeUtils.getEncodeImplClassFilePath(it, applicationId) }
        writer.emitPackage(pkg)
            .emitImports(imports)
            .beginType(
                className,
                "class",
                mutableSetOf(Modifier.PUBLIC, Modifier.FINAL),
            )
        modes.forEachIndexed { index, currentMode ->
            val className = ModeUtils.getEncodeImplClassName(currentMode)
            writer.emitField(
                className,
                "IMPL_$index",
                mutableSetOf(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL),
                "new $className()"
            )
        }
        writer
            .beginMethod(
                String::class.java.simpleName,
                methodName,
                mutableSetOf(Modifier.PUBLIC, Modifier.STATIC),
                String::class.java.simpleName,
                "value",
                String::class.java.simpleName,
                "key",
                "int",
                "mode"
            )
            .emitStatement(decryptStringStatement(modes))
            .endMethod()
            .beginMethod(
                String::class.java.simpleName,
                methodName,
                mutableSetOf(Modifier.PUBLIC, Modifier.STATIC),
                ByteArray::class.java.simpleName,
                "value",
                ByteArray::class.java.simpleName,
                "key",
                "int",
                "mode"
            )
            .emitStatement(decryptBytesStatement(modes))
            .endMethod()
            .endType()
    }

    private fun decryptStringStatement(modes: List<Mode>): String {
        return decryptStatement(modes, "decryptString(value,key)")
    }

    private fun decryptBytesStatement(modes: List<Mode>): String {
        return decryptStatement(modes, "decryptBytes(value,key)")
    }

    private fun decryptStatement(modes: List<Mode>, call: String): String {
        if (modes.size == 1) {
            return "return IMPL_0.$call"
        }
        return buildString {
            append("switch (mode) {\n")
            modes.indices.drop(1).forEach { index ->
                append("            case $index: return IMPL_$index.$call;\n")
            }
            append("            default: return IMPL_0.$call;\n")
            append("        }")
        }
    }

    override fun getFileName(applicationId: String) = "${Constant.PLUGIN_CLASS_NAME}.java"
}
