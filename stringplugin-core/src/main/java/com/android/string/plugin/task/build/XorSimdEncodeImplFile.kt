package com.android.string.plugin.task.build

import com.android.string.plugin.data.Constant
import com.android.string.plugin.mode.Mode
import com.android.string.plugin.task.BaseFile
import com.palantir.javapoet.ArrayTypeName
import com.palantir.javapoet.ClassName
import com.palantir.javapoet.CodeBlock
import com.palantir.javapoet.MethodSpec
import com.palantir.javapoet.TypeName
import com.palantir.javapoet.TypeSpec
import javax.lang.model.element.Modifier

/**
 * SIMD优化的XOR加密算法专用文件生成器
 * 生成的代码充分发挥SIMD批量处理性能优势
 *
 * @author chancey
 * @date 2026/6/19
 **/
class XorSimdEncodeImplFile : BaseFile() {
    override fun getImplClassName() = Constant.XOR_SIMD_IMPL_CLASS_NAME

    override fun buildTypeSpec(applicationId: String, modes: List<Mode>): TypeSpec {
        val byteArrayType = ArrayTypeName.of(TypeName.BYTE)
        return TypeSpec.classBuilder(getImplClassName())
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addSuperinterface(ClassName.bestGuess(Constant.ABSTRACT_CLASS_NAME))
            .addMethod(buildEncryptMethod(byteArrayType))
            .addMethod(buildDecryptMethod(byteArrayType))
            .addMethod(buildGenerateKeyPatternMethod())
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
            .addStatement("byte[] keyBytes = key.getBytes()")
            .addStatement("if (keyBytes.length == 0) return data")
            .addStatement("int dataLen = data.length")
            .addStatement("int keyLen = keyBytes.length")
            .addComment("8字节批量处理 (SIMD风格优化)")
            .addStatement("int longLen = dataLen / 8")
            .beginControlFlow("if (longLen > 0 && keyLen >= 8)")
            .addStatement("long[] keyPattern = generateKeyPattern(keyBytes)")
            .addComment("批量处理8字节块")
            .beginControlFlow("for (int i = 0; i < longLen; i++)")
            .addStatement("int offset = i * 8")
            .addCode(buildDataChunkCode())
            .addStatement("long encrypted = dataChunk ^ keyPattern[i % keyPattern.length]")
            .addStatement("data[offset] = (byte) (encrypted & 0xFF)")
            .addStatement("data[offset + 1] = (byte) ((encrypted >> 8) & 0xFF)")
            .addStatement("data[offset + 2] = (byte) ((encrypted >> 16) & 0xFF)")
            .addStatement("data[offset + 3] = (byte) ((encrypted >> 24) & 0xFF)")
            .addStatement("data[offset + 4] = (byte) ((encrypted >> 32) & 0xFF)")
            .addStatement("data[offset + 5] = (byte) ((encrypted >> 40) & 0xFF)")
            .addStatement("data[offset + 6] = (byte) ((encrypted >> 48) & 0xFF)")
            .addStatement("data[offset + 7] = (byte) ((encrypted >> 56) & 0xFF)")
            .endControlFlow()
            .endControlFlow()
            .addComment("处理剩余字节")
            .beginControlFlow("for (int i = longLen * 8; i < dataLen; i++)")
            .addStatement("data[i] = (byte) (data[i] ^ keyBytes[i % keyLen])")
            .endControlFlow()
            .addStatement("return data")
            .build()
    }

    private fun buildDataChunkCode(): CodeBlock {
        return CodeBlock.builder()
            .add("long dataChunk = ((long) data[offset] & 0xFF)\n")
            .add("               | (((long) data[offset + 1] & 0xFF) << 8)\n")
            .add("               | (((long) data[offset + 2] & 0xFF) << 16)\n")
            .add("               | (((long) data[offset + 3] & 0xFF) << 24)\n")
            .add("               | (((long) data[offset + 4] & 0xFF) << 32)\n")
            .add("               | (((long) data[offset + 5] & 0xFF) << 40)\n")
            .add("               | (((long) data[offset + 6] & 0xFF) << 48)\n")
            .add("               | (((long) data[offset + 7] & 0xFF) << 56);\n")
            .build()
    }

    private fun buildDecryptMethod(byteArrayType: TypeName): MethodSpec {
        return MethodSpec.methodBuilder("decrypt")
            .addAnnotation(Override::class.java)
            .addModifiers(Modifier.PUBLIC)
            .returns(byteArrayType)
            .addParameter(byteArrayType, "data")
            .addParameter(byteArrayType, "key")
            .addStatement("return encrypt(data, new String(key))")
            .build()
    }

    private fun buildGenerateKeyPatternMethod(): MethodSpec {
        val longArrayType = ArrayTypeName.of(TypeName.LONG)
        return MethodSpec.methodBuilder("generateKeyPattern")
            .addModifiers(Modifier.PRIVATE)
            .returns(longArrayType)
            .addParameter(ArrayTypeName.of(TypeName.BYTE), "keyBytes")
            .addStatement("int patternCount = Math.max(1, 256 / keyBytes.length)")
            .addStatement("long[] patterns = new long[patternCount]")
            .beginControlFlow("for (int i = 0; i < patternCount; i++)")
            .addStatement("long pattern = 0")
            .beginControlFlow("for (int j = 0; j < 8; j++)")
            .addStatement("pattern |= ((long) keyBytes[(i * 8 + j) % keyBytes.length] & 0xFF) << (j * 8)")
            .endControlFlow()
            .addStatement("patterns[i] = pattern")
            .endControlFlow()
            .addStatement("return patterns")
            .build()
    }
}