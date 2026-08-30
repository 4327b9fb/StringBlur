package com.android.string.plugin.trasform.visitor

import org.junit.Assert.assertEquals
import org.junit.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode

class SensitiveStringAnalyzerTest {

    @Test
    fun marksStringPassedThroughLocalVariableToNonLastSensitiveArgument() {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "test/SensitiveSample", null, "java/lang/Object", null)
        val method = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "run", "()V", null, null)
        method.visitCode()
        method.visitLdcInsn("ordinary-string")
        method.visitInsn(Opcodes.POP)
        method.visitLdcInsn("reflective-class-name")
        method.visitVarInsn(Opcodes.ASTORE, 0)
        method.visitVarInsn(Opcodes.ALOAD, 0)
        method.visitInsn(Opcodes.ICONST_0)
        method.visitInsn(Opcodes.ACONST_NULL)
        method.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "java/lang/Class",
            "forName",
            "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;",
            false
        )
        method.visitInsn(Opcodes.POP)
        method.visitInsn(Opcodes.RETURN)
        method.visitMaxs(3, 1)
        method.visitEnd()
        writer.visitEnd()

        val classNode = ClassNode(Opcodes.ASM9)
        org.objectweb.asm.ClassReader(writer.toByteArray()).accept(classNode, 0)
        val methodNode = classNode.methods.single { it.name == "run" }

        assertEquals(
            setOf(1),
            SensitiveStringAnalyzer.findSensitiveLdcOrdinals("test/SensitiveSample", methodNode)
        )
    }
}
