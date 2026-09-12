package com.android.string.plugin.trasform.visitor

import com.android.string.plugin.data.Constant
import com.android.string.plugin.trasform.ClassVisitorController
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * 可加密字符串 LDC 的延迟发射基类：LDC 先暂存，观察紧随其后的指令——
 * 若流入敏感 API（[SensitiveApiDetector]）或处于 @KeepString 范围则保持明文，
 * 否则照常加密。除 LDC 与方法调用外的任意指令都会先冲刷暂存串，
 * 保证加解密序列始终占据原 LDC 的栈位置。
 *
 * @author chancey
 * @date   2026/8/30
 **/
abstract class StringDeferringMethodVisitor(
    mv: MethodVisitor,
    protected val controller: ClassVisitorController,
    protected val methodName: String?,
    private val sensitiveLdcOrdinals: Set<Int> = emptySet(),
    initialMaxLocals: Int = 0
) : MethodVisitor(Opcodes.ASM9, mv) {

    private var pending: String? = null
    private var ldcOrdinal: Int = 0
    private var nextLocal: Int = initialMaxLocals

    // 暂存串是否处于 @KeepString 范围，在 LDC 时确定
    private var pendingKeep: Boolean = false

    // 方法级 @KeepString / @EncryptString（注解在 visitCode 之前回调）
    private var methodKeep: Boolean = false
    private var methodEncrypt: Boolean = false

    private fun keepActive(): Boolean = controller.classKeep || methodKeep
    private fun forceActive(): Boolean = controller.classEncrypt || methodEncrypt

    /** 暂存字符串的最终处理；skipReason 非 null 时保持明文并记录原因 */
    protected abstract fun flushPending(value: String, skipReason: String?)

    /** 非可加密 LDC 时的状态复位钩子（如 Clinit 的 temp 标记） */
    protected open fun resetPendingState() {}

    /**
     * PUTFIELD/PUTSTATIC 前的守卫：目标字段被 @KeepString 标注时返回 true，
     * 暂存串将保持明文。默认不启用，由子类按字段类别覆盖。
     */
    protected open fun shouldKeepFieldInsn(
        opcode: Int,
        owner: String?,
        name: String?,
        descriptor: String?
    ): Boolean {
        return false
    }

    /** 直接向下游发射明文 LDC，绕过本类的暂存逻辑；子类的明文分支必须用这个而不是 super.visitLdcInsn */
    protected fun writePlainLdc(value: String) {
        super.visitLdcInsn(value)
    }

    override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor {
        when (descriptor) {
            Constant.ANNOTATION_KEEP_STRING -> methodKeep = true
            Constant.ANNOTATION_ENCRYPT_STRING -> methodEncrypt = true
        }
        return super.visitAnnotation(descriptor, visible)
    }

    override fun visitLdcInsn(value: Any?) {
        flush()
        val isSensitiveString = value is String && ldcOrdinal++ in sensitiveLdcOrdinals
        if (isSensitiveString && !keepActive() && !forceActive()) {
            controller.reportIgnored(methodName, value, REASON_SENSITIVE)
            super.visitLdcInsn(value)
            resetPendingState()
            return
        }
        if (value is String && !value.isEmpty() && (controller.overflow(value) || forceActive())) {
            pending = value
            pendingKeep = keepActive()
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
        if (pending != null) {
            flushWith(
                when {
                    pendingKeep -> REASON_KEEP
                    !forceActive() && controller.isSensitiveCall(owner, name) -> REASON_SENSITIVE
                    else -> null
                }
            )
        }
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
    }

    override fun visitFieldInsn(opcode: Int, owner: String?, name: String?, descriptor: String?) {
        flushBeforeFieldInsn(opcode, owner, name, descriptor)
        super.visitFieldInsn(opcode, owner, name, descriptor)
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

    override fun visitInvokeDynamicInsn(
        name: String?,
        descriptor: String?,
        bootstrapMethodHandle: Handle?,
        vararg bootstrapMethodArguments: Any?
    ) {
        flush()
        if (StringConcatRewriter.rewrite(
                this,
                name,
                descriptor,
                bootstrapMethodHandle,
                bootstrapMethodArguments,
                ::allocateLocals
            )
        ) {
            return
        }
        super.visitInvokeDynamicInsn(
            name,
            descriptor,
            bootstrapMethodHandle,
            *bootstrapMethodArguments
        )
    }

    override fun visitJumpInsn(opcode: Int, label: Label?) {
        flush()
        super.visitJumpInsn(opcode, label)
    }

    override fun visitLabel(label: Label?) {
        flush()
        super.visitLabel(label)
    }

    override fun visitIincInsn(varIndex: Int, increment: Int) {
        flush()
        super.visitIincInsn(varIndex, increment)
    }

    override fun visitTableSwitchInsn(min: Int, max: Int, dflt: Label?, vararg labels: Label?) {
        flush()
        super.visitTableSwitchInsn(min, max, dflt, *labels)
    }

    override fun visitLookupSwitchInsn(dflt: Label?, keys: IntArray?, labels: Array<out Label>?) {
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

    override fun visitLineNumber(line: Int, start: Label?) {
        flush()
        super.visitLineNumber(line, start)
    }

    override fun visitMaxs(maxStack: Int, maxLocals: Int) {
        flush()
        // AGP管道的ClassWriter不重算maxStack，注入代码的栈高水位必须由本插件声明。
        // 注意：注入序列替换的是原方法中1个LDC栈槽，且可能嵌在已有栈帧之上
        // （如 aastore 循环中"数组,数组,下标"已有3槽基线），因此不是取max而是加净增量：
        //  - BYTES内联：构造密文byte[]自身峰值4槽，替换1个LDC槽 → 净增3（最坏情况）
        //  - STRING内联/split调用：getter结果+key+mode占3槽 → 净增2
        //  - concat重写：参数先存入局部变量、栈回基线；append的常量/参数LDC同样走
        //    flush替换路径，最坏净增3
        super.visitMaxs(maxStack + MAX_STACK_HEADROOM, maxOf(maxLocals, nextLocal))
    }

    override fun visitEnd() {
        flush()
        super.visitEnd()
    }

    protected fun flush() {
        flushWith(if (pendingKeep) REASON_KEEP else null)
    }

    /**
     * 冲刷暂存串；字段指令场景先判定目标字段是否被 @KeepString 标注，
     * 以决定明文发射（如 PUTFIELD 到 keep 字段），再交由子类处理字段指令。
     */
    protected fun flushBeforeFieldInsn(
        opcode: Int,
        owner: String?,
        name: String?,
        descriptor: String?
    ) {
        val value = pending
        if (value != null && !pendingKeep && shouldKeepFieldInsn(opcode, owner, name, descriptor)) {
            flushWith(REASON_KEEP)
            return
        }
        flush()
    }

    private fun flushWith(skipReason: String?) {
        val value = pending ?: return
        pending = null
        pendingKeep = false
        flushPending(value, skipReason)
    }

    private fun allocateLocals(types: List<org.objectweb.asm.Type>): IntArray {
        val locals = IntArray(types.size)
        for (index in types.indices.reversed()) {
            locals[index] = nextLocal
            nextLocal += types[index].size
        }
        return locals
    }

    companion object {
        const val REASON_KEEP = "keepString"
        const val REASON_SENSITIVE = "sensitiveApi"

        /** 注入序列从零栈基线算起的峰值（用于全合成方法，如插件生成的<clinit>） */
        const val INJECTED_CODE_MAX_STACK = 4

        /**
         * 注入序列相对原方法已声明maxStack的净增量（见 visitMaxs 注释）：
         * 注入替换1个LDC槽，最坏（BYTES内联）自身峰值4 → 净增3
         */
        const val MAX_STACK_HEADROOM = 3
    }
}
