package com.android.string.plugin.trasform

import com.android.string.plugin.data.Constant
import com.android.string.plugin.field.StringFiled
import com.android.string.plugin.mode.BytesMode
import com.android.string.plugin.mode.Mode
import com.android.string.plugin.mode.SelectionStrategy
import com.android.string.plugin.trasform.visitor.SensitiveStringAnalyzer
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.MethodNode

/**
 * @author chancey
 * @date   2023/9/5   22:23
 **/
class StringBlurClassVisitor(
    cv: ClassVisitor,
    wrapperClass: String,
    wrapperMethod: String,
    key: String,
    bytesMode: BytesMode,
    modes: List<Mode>,
    reportPath: String?,
    minLength: Int,
    skipSensitiveApi: Boolean,
    selectionStrategy: SelectionStrategy,
    performanceWeight: Double,
    securityWeight: Double,
    dataSplitThreshold: Int = 50,
) : ClassVisitor(Opcodes.ASM9, cv) {
    private val controller = ClassVisitorController(
        wrapperClass,
        wrapperMethod,
        key,
        bytesMode,
        modes,
        reportPath,
        minLength,
        skipSensitiveApi,
        selectionStrategy,
        performanceWeight,
        securityWeight,
        dataSplitThreshold
    )

    override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor {
        when (descriptor) {
            Constant.ANNOTATION_KEEP_STRING -> controller.classKeep = true
            Constant.ANNOTATION_ENCRYPT_STRING -> controller.classEncrypt = true
        }
        return super.visitAnnotation(descriptor, visible)
    }

    override fun visit(
        version: Int,
        access: Int,
        name: String?,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?
    ) {
        controller.currentClassName = name
        super.visit(version, access, name, signature, superName, interfaces)
    }

    override fun visitEnd() {
        // 所有方法先缓冲、到类visitEnd统一回放：
        // 这样合成getter命名前已获知本类全部已有方法名（包括排在加密点之后的方法），
        // 可避免$key/$entry_N/$chunk_N_M与原有方法重名导致重复方法签名
        controller.reserveExistingMethodNames(pendingMethods.map { it.name.orEmpty() })
        // 方法名预留：合成的 getter（$key/$entry_N）不能与原类方法重名
        for (methodNode in pendingMethods) {
            val downstreamMv = super.visitMethod(
                methodNode.access,
                methodNode.name,
                methodNode.desc,
                methodNode.signature,
                methodNode.exceptions?.toTypedArray()
            ) ?: continue  // 下游选择跳过该方法
            val sensitiveLdcOrdinals = if (controller.skipSensitiveApi) {
                SensitiveStringAnalyzer.findSensitiveLdcOrdinals(
                    controller.currentClassName,
                    methodNode
                )
            } else {
                emptySet()
            }
            methodNode.accept(
                controller.visitMethod(
                    methodNode.access,
                    downstreamMv,
                    methodNode.name,
                    sensitiveLdcOrdinals,
                    methodNode.maxLocals
                )
            )
        }
        if (controller.isVisitClInitMethod()) {
            controller.visitEnd(
                super.visitMethod(
                    Opcodes.ACC_STATIC,
                    "<clinit>",
                    "()V",
                    null,
                    null
                )
            )
        }
        // 在类处理完成后，为当前类发射split getter方法
        // getter方法直接添加到当前类中，确保被AGP管道处理并编入DEX
        // 注意：getter方法体中已是密文数据，必须直接发射给下游visitor（cv），
        // 绕过本类的visitMethod——否则密文LDC会被当作明文再次加密，
        // getter内多出一层decrypt调用（运行时需解密两次才得到明文，且密文被Base64二次膨胀）
        if (controller.hasSplitGetters()) {
            controller.emitSplitGetters(cv)
        }
        super.visitEnd()
    }

    override fun visitField(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        value: Any?
    ): FieldVisitor {
        controller.visitField(access, name, descriptor, value as? String)
        return object : FieldNode(Opcodes.ASM9, access, name, descriptor, signature, value) {
            override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor {
                when (descriptor) {
                    Constant.ANNOTATION_KEEP_STRING -> controller.markFieldAnnotation(
                        name.orEmpty(),
                        keep = true,
                        force = false
                    )

                    Constant.ANNOTATION_ENCRYPT_STRING -> controller.markFieldAnnotation(
                        name.orEmpty(),
                        keep = false,
                        force = true
                    )
                }
                return super.visitAnnotation(descriptor, visible)
            }

            override fun visitEnd() {
                super.visitEnd()
                val removeConstantValue = name != null &&
                        descriptor == StringFiled.DESC &&
                        value is String &&
                        !controller.classKeep &&
                        !controller.isKeepStaticField(name) &&
                        (controller.isForceStaticField(name) || controller.overflow(value))
                val fieldVisitor = emitField(
                    access,
                    name,
                    descriptor,
                    signature,
                    if (removeConstantValue) null else value
                )
                accept(object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitField(
                        access: Int,
                        name: String?,
                        descriptor: String?,
                        signature: String?,
                        value: Any?
                    ): FieldVisitor {
                        return fieldVisitor
                    }
                })
            }
        }
    }

    private fun emitField(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        value: Any?
    ): FieldVisitor {
        return super.visitField(access, name, descriptor, signature, value)
    }

    // 全部方法缓冲到类visitEnd再统一回放（见visitEnd注释）
    private val pendingMethods = mutableListOf<MethodNode>()

    // （合成字段名预留机制已随 getter 缓存一并移除）

    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor {
        // 仅缓冲，不创建下游方法visitor——下游在visitEnd回放时才逐个打开
        return object : MethodNode(Opcodes.ASM9, access, name, descriptor, signature, exceptions) {
            override fun visitEnd() {
                super.visitEnd()
                pendingMethods += this
            }
        }
    }
}
