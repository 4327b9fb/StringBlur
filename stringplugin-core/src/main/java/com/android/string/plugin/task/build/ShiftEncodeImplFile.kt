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
 * 位移加密算法专用文件生成器
 * 生成的代码使用密钥低位进行位移操作
 *
 * @author chancey
 * @date 2023/9/5
 **/
class ShiftEncodeImplFile : BaseFile() {
    override fun getImplClassName() = Constant.SHIFT_IMPL_CLASS_NAME

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
                    .addStatement("return shift(data, key.getBytes(), 1)")
                    .build()
            )
            .addMethod(
                MethodSpec.methodBuilder("decrypt")
                    .addAnnotation(Override::class.java)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(byteArrayType)
                    .addParameter(byteArrayType, "data")
                    .addParameter(byteArrayType, "key")
                    .addStatement("return shift(data, key, -1)")
                    .build()
            )
            .addMethod(
                MethodSpec.methodBuilder("shift")
                    .addModifiers(Modifier.PRIVATE)
                    .returns(byteArrayType)
                    .addParameter(byteArrayType, "data")
                    .addParameter(byteArrayType, "key")
                    .addParameter(Int::class.javaPrimitiveType, "direction")
                    .addStatement("int lenKey = key.length")
                    .beginControlFlow("for (int i = 0; i < data.length; i++)")
                    .addStatement("int offset = key[i % lenKey] & 0x0F")
                    .addStatement("data[i] = (byte) (data[i] + direction * offset)")
                    .endControlFlow()
                    .addStatement("return data")
                    .build()
            )
            .build()
    }
}