package com.android.string.plugin.trasform.visitor

import com.android.string.plugin.trasform.ClassVisitorController
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * 可加密字符串 LDC 的延迟发射基类：LDC 先暂存，观察紧随其后的指令——
 * 若流入敏感 API（[SensitiveApiDetector]）则保持明文，否则照常加密。
 * 除 LDC 与方法调用外的任意指令都会先冲刷暂存串，
 * 保证加解密序列始终占据原 LDC 的栈位置。
 *
 * @author chancey
 * @date   2026/8/30
 **/
abstract class StringDeferringMethodVisitor(
    mv: MethodVisitor,
    protected val controller: ClassVisitorController,
    protected val methodName: String?
) : MethodVisitor(Opcodes.ASM9, mv) {

    private var pending: String? = null

    /** 暂存字符串的最终处理；sensitive 为 true 时须保持明文 */
    protected abstract fun flushPending(value: String, sensitive: Boolean)

    /** 非可加密 LDC 时的状态复位钩子（如 Clinit 的 temp 标记） */
    protected open fun resetPendingState() {}

    /** 直接向下游发射明文 LDC，绕过本类的暂存逻辑；子类的 sensitive 分支必须用这个而不是 super.visitLdcInsn */
    protected fun writePlainLdc(value: String) {
        super.visitLdcInsn(value)
    }

    override fun visitLdcInsn(value: Any?) {
        flush()
        if (value is String && controller.overflow(value)) {
            pending = value
        } else {
            controller.reportIgnoredLdc(methodName, value)
            super.visitLdcInsn(value)
            resetPendingState()
        }
    }

    override fun visitMethodInsn(
        opcode: Int,
        owner: String?,
        name: String?,
        descriptor: String?,
        isInterface: Boolean
    ) {
        val value = pending
        if (value != null) {
            pending = null
            flushPending(value, controller.isSensitiveCall(owner, name))
        }
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
    }

    override fun visitInsn(opcode: Int) {
        flush()
        super.visitInsn(opcode)
    }

    override fun visitIntInsn(opcode: Int, operand: Int) {
        flush()
        super.visitIntInsn(opcode, operand)
    }

    override fun visitVarInsn(opcode: Int, varIndex: Int) {
        flush()
        super.visitVarInsn(opcode, varIndex)
    }

    override fun visitTypeInsn(opcode: Int, type: String?) {
        flush()
        super.visitTypeInsn(opcode, type)
    }

    override fun visitFieldInsn(opcode: Int, owner: String?, name: String?, descriptor: String?) {
        flush()
        super.visitFieldInsn(opcode, owner, name, descriptor)
    }

    override fun visitInvokeDynamicInsn(
        name: String?,
        descriptor: String?,
        bootstrapMethodHandle: org.objectweb.asm.Handle?,
        vararg bootstrapMethodArguments: Any?
    ) {
        flush()
        super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, *bootstrapMethodArguments)
    }

    override fun visitJumpInsn(opcode: Int, label: org.objectweb.asm.Label?) {
        flush()
        super.visitJumpInsn(opcode, label)
    }

    override fun visitLabel(label: org.objectweb.asm.Label?) {
        flush()
        super.visitLabel(label)
    }

    override fun visitIincInsn(varIndex: Int, increment: Int) {
        flush()
        super.visitIincInsn(varIndex, increment)
    }

    override fun visitTableSwitchInsn(min: Int, max: Int, dflt: org.objectweb.asm.Label?, vararg labels: org.objectweb.asm.Label?) {
        flush()
        super.visitTableSwitchInsn(min, max, dflt, *labels)
    }

    override fun visitLookupSwitchInsn(dflt: org.objectweb.asm.Label?, keys: IntArray?, labels: Array<out org.objectweb.asm.Label>?) {
        flush()
        super.visitLookupSwitchInsn(dflt, keys, labels)
    }

    override fun visitMultiANewArrayInsn(descriptor: String?, numDimensions: Int) {
        flush()
        super.visitMultiANewArrayInsn(descriptor, numDimensions)
    }

    override fun visitFrame(
        type: Int,
        numLocal: Int,
        local: Array<out Any?>?,
        numStack: Int,
        stack: Array<out Any?>?
    ) {
        flush()
        super.visitFrame(type, numLocal, local, numStack, stack)
    }

    override fun visitLineNumber(line: Int, start: org.objectweb.asm.Label?) {
        flush()
        super.visitLineNumber(line, start)
    }

    override fun visitMaxs(maxStack: Int, maxLocals: Int) {
        flush()
        super.visitMaxs(maxStack, maxLocals)
    }

    override fun visitEnd() {
        flush()
        super.visitEnd()
    }

    protected fun flush() {
        val value = pending ?: return
        pending = null
        flushPending(value, false)
    }
}
