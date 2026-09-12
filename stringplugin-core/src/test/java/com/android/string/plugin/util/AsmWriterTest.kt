package com.android.string.plugin.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodNode

class AsmWriterTest {

    private val writer = AsmWriter("test/Wrapper", "decrypt")

    private fun emitInt(value: Int): AbstractInsnNode {
        val mn = MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC, "m", "()V", null, null)
        writer.write(value, mn)
        val real = mn.instructions.toArray().filter { it.opcode != Opcodes.NOP }
        assertEquals("应只发射一条指令，实际: ${real.map { it.opcode }}", 1, real.size)
        return real.single()
    }

    @Test
    fun writesShortestIntInstructionPerRange() {
        // -1..5 → ICONST（单字节零操作数指令）
        assertEquals(Opcodes.ICONST_M1, emitInt(-1).opcode)
        assertEquals(Opcodes.ICONST_0, emitInt(0).opcode)
        assertEquals(Opcodes.ICONST_5, emitInt(5).opcode)

        // -128..127 → BIPUSH（2字节）
        (emitInt(-128) as IntInsnNode).let {
            assertEquals(Opcodes.BIPUSH, it.opcode); assertEquals(-128, it.operand)
        }
        (emitInt(127) as IntInsnNode).let {
            assertEquals(Opcodes.BIPUSH, it.opcode); assertEquals(127, it.operand)
        }

        // -32768..32767 → SIPUSH（3字节）；历史bug：分支误写为Short.MAX_VALUE..Short.MAX_VALUE，
        // 导致128..32766范围错误回退到LDC（常量池条目+2字节）
        (emitInt(-32768) as IntInsnNode).let {
            assertEquals(Opcodes.SIPUSH, it.opcode); assertEquals(-32768, it.operand)
        }
        (emitInt(128) as IntInsnNode).let {
            assertEquals(Opcodes.SIPUSH, it.opcode); assertEquals(128, it.operand)
        }
        (emitInt(32767) as IntInsnNode).let {
            assertEquals(Opcodes.SIPUSH, it.opcode); assertEquals(32767, it.operand)
        }

        // 超出short范围 → LDC
        assertTrue(emitInt(32768) is LdcInsnNode)
        assertTrue(emitInt(-32769) is LdcInsnNode)
    }
}
