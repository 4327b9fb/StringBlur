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
 * 快速旋转加密算法专用文件生成器
 * 生成的代码充分发挥位旋转的性能优势
 *
 * @author chancey
 * @date 2026/6/19
 **/
class FastRotEncodeImplFile : BaseFile() {
    override fun getImplClassName() = Constant.FAST_ROT_IMPL_CLASS_NAME

    override fun buildTypeSpec(applicationId: String, modes: List<Mode>): TypeSpec {
        val byteArrayType = ArrayTypeName.of(TypeName.BYTE)
        return TypeSpec.classBuilder(getImplClassName())
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addSuperinterface(ClassName.bestGuess(Constant.ABSTRACT_CLASS_NAME))
            .addMethod(buildEncryptMethod(byteArrayType))
            .addMethod(buildDecryptMethod(byteArrayType))
            .addMethod(buildRotateLeftMethod())
            .addMethod(buildRotateRightMethod())
            .build()
    }

    private fun buildEncryptMethod(byteArrayType: TypeName): MethodSpec {
        return MethodSpec.methodBuilder("encrypt")
            .addAnnotation(Override::class.java)
            .addModifiers(Modifier.PUBLIC)
            .returns(byteArrayType)
            .addParameter(byteArrayType, "data")
            .addParameter(String::class.java, "key")
            .addStatement("if (data == null || data.length == 0 || key == null) return data")
            .addComment("使用key生成1-7的旋转值")
            .addStatement("int rotation = Math.abs(key.hashCode()) % 7 + 1")
            .beginControlFlow("for (int i = 0; i < data.length; i++)")
            .addStatement("data[i] = rotateLeft(data[i], rotation)")
            .endControlFlow()
            .addStatement("return data")
            .build()
    }

    private fun buildDecryptMethod(byteArrayType: TypeName): MethodSpec {
        return MethodSpec.methodBuilder("decrypt")
            .addAnnotation(Override::class.java)
            .addModifiers(Modifier.PUBLIC)
            .returns(byteArrayType)
            .addParameter(byteArrayType, "data")
            .addParameter(byteArrayType, "key")
            .addStatement("if (data == null || data.length == 0 || key == null) return data")
            .addStatement("int rotation = Math.abs(new String(key).hashCode()) % 7 + 1")
            .beginControlFlow("for (int i = 0; i < data.length; i++)")
            .addStatement("data[i] = rotateRight(data[i], rotation)")
            .endControlFlow()
            .addStatement("return data")
            .build()
    }

    private fun buildRotateLeftMethod(): MethodSpec {
        return MethodSpec.methodBuilder("rotateLeft")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(TypeName.BYTE)
            .addParameter(TypeName.BYTE, "value")
            .addParameter(Int::class.javaPrimitiveType, "positions")
            .addStatement("return (byte) (((value & 0xFF) << positions) | ((value & 0xFF) >>> (8 - positions)))")
            .build()
    }

    private fun buildRotateRightMethod(): MethodSpec {
        return MethodSpec.methodBuilder("rotateRight")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(TypeName.BYTE)
            .addParameter(TypeName.BYTE, "value")
            .addParameter(Int::class.javaPrimitiveType, "positions")
            .addStatement("return (byte) (((value & 0xFF) >>> positions) | ((value & 0xFF) << (8 - positions)))")
            .build()
    }
}