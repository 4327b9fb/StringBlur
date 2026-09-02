package com.android.string.plugin.task.build

import com.android.string.plugin.data.Constant
import com.android.string.plugin.mode.Mode
import com.android.string.plugin.task.BaseFile
import com.palantir.javapoet.ArrayTypeName
import com.palantir.javapoet.ClassName
import com.palantir.javapoet.MethodSpec
import com.palantir.javapoet.TypeName
import com.palantir.javapoet.TypeSpec
import javax.lang.model.element.Modifier

/**
 * 字节反转加密算法专用文件生成器
 * 生成的代码使用反转算法实现最高性能
 *
 * @author chancey
 * @date 2023/9/5
 **/
class ReverseEncodeImplFile : BaseFile() {
    override fun getImplClassName() = Constant.REVERSE_IMPL_CLASS_NAME

    override fun buildTypeSpec(applicationId: String, modes: List<Mode>): TypeSpec {
        val byteArrayType = ArrayTypeName.of(TypeName.BYTE)
        return TypeSpec.classBuilder(getImplClassName())
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addSuperinterface(ClassName.bestGuess(Constant.ABSTRACT_CLASS_NAME))
            .addMethod(
                MethodSpec.methodBuilder("encrypt")
                    .addAnnotation(Override::class.java)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(byteArrayType)
                    .addParameter(byteArrayType, "data")
                    .addParameter(String::class.java, "key")
                    .addStatement("return reverse(data)")
                    .build()
            )
            .addMethod(
                MethodSpec.methodBuilder("decrypt")
                    .addAnnotation(Override::class.java)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(byteArrayType)
                    .addParameter(byteArrayType, "data")
                    .addParameter(byteArrayType, "key")
                    .addStatement("return reverse(data)")
                    .build()
            )
            .addMethod(
                MethodSpec.methodBuilder("reverse")
                    .addModifiers(Modifier.PRIVATE)
                    .returns(byteArrayType)
                    .addParameter(byteArrayType, "data")
                    .addStatement("int left = 0")
                    .addStatement("int right = data.length - 1")
                    .beginControlFlow("while (left < right)")
                    .addStatement("byte temp = data[left]")
                    .addStatement("data[left] = data[right]")
                    .addStatement("data[right] = temp")
                    .addStatement("left++")
                    .addStatement("right--")
                    .endControlFlow()
                    .addStatement("return data")
                    .build()
            )
            .build()
    }
}