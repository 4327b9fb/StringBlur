package com.android.string.plugin.trasform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode

class SplitGetterEmitterTest {

    @Test
    fun emitsGettersAndClearsPendingEntries() {
        val emitter = SplitGetterEmitter()
        val stringGetter = emitter.registerEntry("encrypted-string", false)
        val bytesGetter = emitter.registerEntry(ByteArray(3) { it.toByte() }, true)
        assertTrue("注册后应有待发射条目", emitter.hasPending())

        val writer = ClassWriter(0)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "test/Cls", null, "java/lang/Object", null)
        emitter.emitGetters(writer, "test/Cls")
        writer.visitEnd()

        assertFalse("发射getter后不应再有待处理条目", emitter.hasPending())

        val classNode = ClassNode(Opcodes.ASM9)
        ClassReader(writer.toByteArray()).accept(classNode, 0)
        val methods = classNode.methods.associate { it.name to it.desc }
        // 小条目直接由$entry_N承载，不生成$chunk
        assertEquals("()Ljava/lang/String;", methods["\$entry_0"])
        assertEquals("()[B", methods["\$entry_1"])
        assertEquals("\$entry_0", stringGetter)
        assertEquals("\$entry_1", bytesGetter)
        assertTrue("小条目不应生成chunk方法", methods.keys.none { it.startsWith("\$chunk_") })
    }

    @Test
    fun keyGetterIsGeneratedOnceAndClearedAfterEmit() {
        val emitter = SplitGetterEmitter()
        assertFalse("未请求key getter时不应有待发射内容", emitter.hasPending())

        // 多次请求返回同名，且即使没有split条目也需要发射
        assertEquals("\$key", emitter.requestKeyGetter("test-key"))
        assertEquals("\$key", emitter.requestKeyGetter("test-key"))
        assertTrue(emitter.hasPending())

        val writer = ClassWriter(0)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "test/Cls", null, "java/lang/Object", null)
        emitter.emitGetters(writer, "test/Cls")
        writer.visitEnd()

        assertFalse("发射后不应再有待处理内容", emitter.hasPending())

        val classNode = ClassNode(Opcodes.ASM9)
        ClassReader(writer.toByteArray()).accept(classNode, 0)
        val keyMethods = classNode.methods.filter { it.name == "\$key" }
        assertEquals("key getter全类只应生成一次", 1, keyMethods.size)
        assertEquals("()[B", keyMethods.single().desc)
    }

    @Test
    fun oversizedEntriesAreSplitIntoChunksWithAggregator() {
        val emitter = SplitGetterEmitter()
        // 10000字节 → ceil(10000/4096)=3个chunk；5000字符 → 2个chunk
        emitter.registerEntry(ByteArray(10000) { (it % 256).toByte() }, true)
        emitter.registerEntry("x".repeat(5000), false)

        val writer = ClassWriter(0)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "test/Cls", null, "java/lang/Object", null)
        emitter.emitGetters(writer, "test/Cls")
        writer.visitEnd()

        val classNode = ClassNode(Opcodes.ASM9)
        ClassReader(writer.toByteArray()).accept(classNode, 0)
        val methods = classNode.methods.associate { it.name to it.desc }

        // 聚合方法（调用点协议不变）
        assertEquals("()[B", methods["\$entry_0"])
        assertEquals("()Ljava/lang/String;", methods["\$entry_1"])
        // byte[]条目：3个chunk
        assertEquals("()[B", methods["\$chunk_0_0"])
        assertEquals("()[B", methods["\$chunk_0_1"])
        assertEquals("()[B", methods["\$chunk_0_2"])
        // String条目：2个chunk
        assertEquals("()Ljava/lang/String;", methods["\$chunk_1_0"])
        assertEquals("()Ljava/lang/String;", methods["\$chunk_1_1"])
    }

    @Test
    fun emittersDoNotShareStateAcrossClasses() {
        // 每个类持有独立的emitter实例：一个类注册的条目不能泄漏到另一个类，
        // 这是per-class实例设计替代旧单例clear()清理的核心保证
        val emitterA = SplitGetterEmitter()
        val emitterB = SplitGetterEmitter()

        emitterA.registerEntry("stale-data", false)

        assertTrue(emitterA.hasPending())
        assertFalse("不同类的emitter之间不应共享条目", emitterB.hasPending())

        // 各自独立计数，getter名均从$entry_0开始
        assertEquals("\$entry_0", emitterB.registerEntry("other", false))
    }

    @Test
    fun emitNothingWhenNoEntriesRegistered() {
        val emitter = SplitGetterEmitter()
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "test/Empty", null, "java/lang/Object", null)
        emitter.emitGetters(writer, "test/Empty")
        writer.visitEnd()

        val classNode = ClassNode(Opcodes.ASM9)
        ClassReader(writer.toByteArray()).accept(classNode, 0)
        assertTrue("无条目时不应生成任何getter方法", classNode.methods.isEmpty())
    }

    @Test
    fun namesCollidingWithExistingMethods_areDisambiguatedWithSuffix() {
        val emitter = SplitGetterEmitter()
        // 被转换类已存在同名方法（方法名含'$'在字节码层面完全合法）
        emitter.reserveExistingNames(listOf("\$key", "\$entry_0", "\$chunk_0_0"))

        // key getter：$key 被占 → $key$0
        assertEquals("\$key\$0", emitter.requestKeyGetter("test-key"))

        // 10000字节 → 3个chunk；条目名$entry_0被占 → $entry_0$0；
        // chunk名$chunk_0_0被占 → $chunk_0_0$0，其余正常
        val entryName = emitter.registerEntry(ByteArray(10000) { (it % 256).toByte() }, true)
        assertEquals("\$entry_0\$0", entryName)

        val writer = ClassWriter(0)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "test/Cls", null, "java/lang/Object", null)
        emitter.emitGetters(writer, "test/Cls")
        writer.visitEnd()

        val classNode = ClassNode(Opcodes.ASM9)
        ClassReader(writer.toByteArray()).accept(classNode, 0)
        val methods = classNode.methods.associate { it.name to it.desc }

        assertEquals("()[B", methods["\$key\$0"])
        assertEquals("()[B", methods["\$entry_0\$0"])
        assertEquals("()[B", methods["\$chunk_0_0\$0"])   // 被占，加后缀
        assertEquals("()[B", methods["\$chunk_0_1"])      // 未占，原名
        assertEquals("()[B", methods["\$chunk_0_2"])
        assertTrue(
            "不应再出现未消歧的保留名",
            methods.keys.none { it == "\$key" || it == "\$entry_0" || it == "\$chunk_0_0" })
    }
}
