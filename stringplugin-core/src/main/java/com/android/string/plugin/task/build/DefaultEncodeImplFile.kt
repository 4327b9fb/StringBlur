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
 * IString接口实现类，内容见[com.android.string.plugin.demo_files.DefaultEncodeImpl]
 * @author chancey
 * @date   2023/9/5   18:35
 **/
class DefaultEncodeImplFile : BaseFile() {
    override fun getImplClassName() = Constant.DEFAULT_IMPL_CLASS_NAME

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
                    .addStatement("int lenKey = key.length()")
                    .addStatement("int j = 0")
                    .beginControlFlow("for (int i = 0; i < data.length; i++)")
                    .beginControlFlow("if (j >= lenKey)")
                    .addStatement("j = 0")
                    .endControlFlow()
                    .addStatement("data[i] = (byte) (data[i] + key.charAt(j))")
                    .addStatement("j++")
                    .endControlFlow()
                    .addStatement("return data")
                    .build()
            )
            .addMethod(
                MethodSpec.methodBuilder("decrypt")
                    .addAnnotation(Override::class.java)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(byteArrayType)
                    .addParameter(byteArrayType, "data")
                    .addParameter(byteArrayType, "key")
                    .addStatement("int lenKey = key.length")
                    .addStatement("int j = 0")
                    .beginControlFlow("for (int i = 0; i < data.length; i++)")
                    .beginControlFlow("if (j >= lenKey)")
                    .addStatement("j = 0")
                    .endControlFlow()
                    .addStatement("data[i] = (byte) (data[i] - key[j])")
                    .addStatement("j++")
                    .endControlFlow()
                    .addStatement("return data")
                    .build()
            )
            .build()
    }
}