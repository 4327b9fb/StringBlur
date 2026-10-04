package com.android.string.plugin

import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * 测试用：记录 MethodVisitor 收到的关键指令（LDC 常量、方法调用）。
 */
class RecordingMethodVisitor : MethodVisitor(Opcodes.ASM9) {
    val ldc = mutableListOf<Any>()
    val calls = mutableListOf<Triple<String, String, String>>()
    val opcodes = mutableListOf<Int>()

    override fun visitLdcInsn(value: Any?) {
        if (value != null) ldc.add(value)
    }

    override fun visitMethodInsn(
        opcode: Int,
        owner: String?,
        name: String?,
        descriptor: String?,
        isInterface: Boolean
    ) {
        calls.add(Triple(owner!!, name!!, descriptor!!))
    }

    override fun visitInsn(opcode: Int) {
        opcodes.add(opcode)
    }
}
