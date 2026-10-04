package com.android.string.plugin.util

import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * @author chancey
 * @date   2024/1/12   14:08
 **/
class AsmWriter(private val className: String, private val methodName: String = "decrypt") {

    fun write(data: String, key: String, mv: MethodVisitor) {
        write(data, key, 0, mv)
    }

    fun write(data: String, key: String, modeIndex: Int, mv: MethodVisitor) {
        mv.visitLdcInsn(data)
        mv.visitLdcInsn(key)
        write(modeIndex, mv)
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            className,
            methodName,
            "(Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/String;",
            false
        )
    }

    fun write(data: ByteArray, key: String, mv: MethodVisitor) {
        write(data, key, 0, mv)
    }

    fun write(data: ByteArray, key: String, modeIndex: Int, mv: MethodVisitor) {
        write(data, mv)
        write(key.toByteArray(), mv)
        write(modeIndex, mv)
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            className,
            methodName,
            "([B[BI)Ljava/lang/String;",
            false
        )
    }

    /**
     * LONG_PRNG 模式：写入 long 常量 + 加密数据 getter 调用 + 解密调用
     *
     * 字节码序列：
     *   ldc2_w <long_value>
     *   invoke-static CurrentClass.$longData()[B
     *   invoke-static WrapperClass.{methodName}Long(J[B)Ljava/lang/String;
     *
     * 注意：{methodName}Long 签名为 (J[B)，无 mode 参数（LONG_PRNG 索引在编译期已知）
     *
     * @param longValue 加密后的 long 值
     * @param dataGetterName 加密数据 getter 方法名（由 LongPrngDataEmitter 生成）
     * @param currentClassName 当前被加密类的内部名
     * @param mv 方法访问器
     */
    fun writeLong(longValue: Long, dataGetterName: String, currentClassName: String, mv: MethodVisitor) {
        // ldc2_w <long_value>
        mv.visitLdcInsn(longValue)
        // invoke-static CurrentClass.$longData()[B
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            currentClassName,
            dataGetterName,
            "()[B",
            false
        )
        // invoke-static WrapperClass.decryptLong(J[B)Ljava/lang/String;
        // 方法名 = 配置的 wrapperMethodName + "Long"（与 StringBlurFile 生成的入口方法一致）
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            className,
            methodName + "Long",
            "(J[B)Ljava/lang/String;",
            false
        )
    }

    fun write(value: ByteArray, mv: MethodVisitor) {
        write(value.size, mv)
        mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_BYTE)
        var i = 0
        while (i < value.size) {
            mv.visitInsn(Opcodes.DUP)
            write(i, mv)
            write(value[i].toInt(), mv)
            mv.visitInsn(Type.BYTE_TYPE.getOpcode(Opcodes.IASTORE))
            i++
        }
    }

    fun write(value: Int, mv: MethodVisitor) {
        when (value) {
            in -1..5 -> mv.visitInsn(Opcodes.ICONST_0 + value)
            in Byte.MIN_VALUE..Byte.MAX_VALUE -> mv.visitIntInsn(Opcodes.BIPUSH, value)
            in Short.MIN_VALUE..Short.MAX_VALUE -> mv.visitIntInsn(Opcodes.SIPUSH, value)
            else -> mv.visitLdcInsn(value)
        }
    }
}