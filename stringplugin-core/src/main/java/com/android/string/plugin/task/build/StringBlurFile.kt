package com.android.string.plugin.task.build

import com.android.string.plugin.data.Constant
import com.android.string.plugin.mode.Mode
import com.android.string.plugin.task.BaseFile
import com.android.string.plugin.util.ModeUtils
import com.palantir.javapoet.ArrayTypeName
import com.palantir.javapoet.ClassName
import com.palantir.javapoet.CodeBlock
import com.palantir.javapoet.FieldSpec
import com.palantir.javapoet.JavaFile
import com.palantir.javapoet.MethodSpec
import com.palantir.javapoet.TypeName
import com.palantir.javapoet.TypeSpec
import java.io.File
import javax.lang.model.element.Modifier

/**
 * 生成解密调用类，内容见[com.android.string.plugin.demo_files.StringBlur]。
 * 类名与解密方法名由[com.android.string.plugin.util.EntryNames]按配置派生，
 * 不再使用固定的 StringBlur/decrypt。
 * @author chancey
 * @date   2023/9/5   17:24
 **/
class StringBlurFile : BaseFile() {

    override fun getImplClassName() = Constant.PLUGIN_CLASS_NAME

    fun createEntry(
        baseDir: File,
        applicationId: String,
        modes: List<Mode>,
        className: String,
        methodName: String
    ) {
        val typeSpec = buildTypeSpec(applicationId, modes, className, methodName)
        val pkg = Constant.PLUGIN_CLASS_PACKAGE.format(applicationId)
        JavaFile.builder(pkg, typeSpec).build().writeTo(baseDir)
    }

    override fun create(baseDir: File, applicationId: String, modes: List<Mode>) {
        createEntry(baseDir, applicationId, modes, getImplClassName(), "decrypt")
    }

    override fun buildTypeSpec(applicationId: String, modes: List<Mode>): TypeSpec {
        return buildTypeSpec(applicationId, modes, getImplClassName(), "decrypt")
    }

    private fun buildTypeSpec(
        applicationId: String,
        modes: List<Mode>,
        className: String,
        methodName: String
    ): TypeSpec {
        val pkg = Constant.PLUGIN_CLASS_PACKAGE.format(applicationId)
        val fields = modes.mapIndexed { index, currentMode ->
            val implClassName = ClassName.get(pkg, ModeUtils.getEncodeImplClassName(currentMode))
            FieldSpec.builder(
                implClassName,
                "IMPL_$index",
                Modifier.PRIVATE,
                Modifier.STATIC,
                Modifier.FINAL
            )
                .initializer("new \$T()", implClassName)
                .build()
        }

        return TypeSpec.classBuilder(className)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addFields(fields)
            .addMethod(buildDecryptStringMethod(modes, methodName))
            .addMethod(buildDecryptBytesMethod(modes, methodName))
            .build()
    }

    private fun buildDecryptStringMethod(modes: List<Mode>, methodName: String): MethodSpec {
        return MethodSpec.methodBuilder(methodName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(String::class.java)
            .addParameter(String::class.java, "value")
            .addParameter(String::class.java, "key")
            .addParameter(Int::class.javaPrimitiveType, "mode")
            .addCode(buildDecryptCode(modes, "decryptString(value,key)"))
            .build()
    }

    private fun buildDecryptBytesMethod(modes: List<Mode>, methodName: String): MethodSpec {
        val byteArrayType = ArrayTypeName.of(TypeName.BYTE)
        return MethodSpec.methodBuilder(methodName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(String::class.java)
            .addParameter(byteArrayType, "value")
            .addParameter(byteArrayType, "key")
            .addParameter(Int::class.javaPrimitiveType, "mode")
            .addCode(buildDecryptCode(modes, "decryptBytes(value,key)"))
            .build()
    }

    private fun buildDecryptCode(modes: List<Mode>, call: String): CodeBlock {
        if (modes.size == 1) {
            return CodeBlock.of("return IMPL_0.\$L;\n", call)
        }
        val builder = CodeBlock.builder()
        builder.beginControlFlow("switch (mode)")
        modes.indices.drop(1).forEach { index ->
            builder.addStatement("case \$L: return IMPL_\$L.\$L", index, index, call)
        }
        builder.addStatement("default: return IMPL_0.\$L", call)
        builder.endControlFlow()
        return builder.build()
    }
}