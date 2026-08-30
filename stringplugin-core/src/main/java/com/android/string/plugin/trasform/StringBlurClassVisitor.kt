package com.android.string.plugin.trasform

import com.android.string.plugin.data.Constant
import com.android.string.plugin.mode.Mode
import com.android.string.plugin.mode.BytesMode
import com.android.string.plugin.mode.SelectionStrategy
import com.android.string.plugin.trasform.visitor.SensitiveStringAnalyzer
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
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
) : ClassVisitor(Opcodes.ASM9, cv) {
    private val controller = ClassVisitorController(wrapperClass, wrapperMethod, key, bytesMode, modes, reportPath, minLength, skipSensitiveApi, selectionStrategy, performanceWeight, securityWeight)

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
        val fieldVisitor = super.visitField(access, name, descriptor, signature, value)
        if (name == null) {
            return fieldVisitor
        }
        return object : FieldVisitor(Opcodes.ASM9, fieldVisitor) {
            override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor {
                when (descriptor) {
                    Constant.ANNOTATION_KEEP_STRING -> controller.markFieldAnnotation(name, keep = true, force = false)
                    Constant.ANNOTATION_ENCRYPT_STRING -> controller.markFieldAnnotation(name, keep = false, force = true)
                }
                return super.visitAnnotation(descriptor, visible)
            }
        }
    }

    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor {
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
            ?: return super.visitMethod(access, name, descriptor, signature, exceptions)
        return object : MethodNode(Opcodes.ASM9, access, name, descriptor, signature, exceptions) {
            override fun visitEnd() {
                super.visitEnd()
                val sensitiveLdcOrdinals = SensitiveStringAnalyzer.findSensitiveLdcOrdinals(
                    controller.currentClassName,
                    this
                )
                accept(controller.visitMethod(access, mv, name, sensitiveLdcOrdinals))
            }
        }
    }
}
