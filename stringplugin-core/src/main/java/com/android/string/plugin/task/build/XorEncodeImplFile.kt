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
 * 生成java代码
 * IString接口实现类，内容见[com.android.string.plugin.demo_files.XorEncodeImpl]
 * @author chancey
 * @date   2023/9/5   18:35
 **/
class XorEncodeImplFile : BaseFile() {
    override fun getImplClassName() = Constant.XOR_IMPL_CLASS_NAME

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
                    .addStatement("return xor(data, key.getBytes())")
                    .build()
            )
            .addMethod(
                MethodSpec.methodBuilder("decrypt")
                    .addAnnotation(Override::class.java)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(byteArrayType)
                    .addParameter(byteArrayType, "data")
                    .addParameter(byteArrayType, "key")
                    .addStatement("return xor(data, key)")
                    .build()
            )
            .addMethod(
                MethodSpec.methodBuilder("xor")
                    .addModifiers(Modifier.PRIVATE)
                    .returns(byteArrayType)
                    .addParameter(byteArrayType, "data")
                    .addParameter(byteArrayType, "key")
                    .addStatement("int len = data.length")
                    .addStatement("int lenKey = key.length")
                    .addStatement("int i = 0")
                    .addStatement("int j = 0")
                    .beginControlFlow("while (i < len)")
                    .beginControlFlow("if (j >= lenKey)")
                    .addStatement("j = 0")
                    .endControlFlow()
                    .addStatement("data[i] = (byte) (data[i] ^ key[j])")
                    .addStatement("i++")
                    .addStatement("j++")
                    .endControlFlow()
                    .addStatement("return data")
                    .build()
            )
            .build()
    }
}