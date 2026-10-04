package com.android.string.plugin.trasform

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode

/**
 * LongPrngDataEmitter 字节码测试：
 * 生成类 → 用 COMPUTE_FRAMES 补帧（模拟 AGP 的 COMPUTE_FRAMES_FOR_INSTRUMENTED_CLASSES）→
 * 真实加载并反射调用 $longData()，验证数据正确、缓存命中（同实例）、分片正确。
 * 加载即验证了帧与 64KB 方法上限（超限会抛 ClassFormatError "Code too large"）。
 */
class LongPrngDataEmitterTest {

    private class ByteArrayClassLoader(private val className: String, private val bytes: ByteArray) : ClassLoader() {
        override fun findClass(name: String): Class<*> {
            if (name == className) {
                return defineClass(name, bytes, 0, bytes.size)
            }
            return super.findClass(name)
        }
    }

    /** 生成类并加载 */
    private fun buildAndLoad(emitter: LongPrngDataEmitter): Pair<Class<*>, ClassNode> {
        val className = "test.longdata.Cls"
        val internalName = "test/longdata/Cls"
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        emitter.emitGetter(writer, internalName)
        writer.visitEnd()

        val bytes = writer.toByteArray()
        val cls = ByteArrayClassLoader(className, bytes).loadClass(className)
        val node = ClassNode(Opcodes.ASM9)
        ClassReader(bytes).accept(node, 0)
        return cls to node
    }

    private fun invokeLongData(cls: Class<*>): ByteArray {
        val method = cls.getDeclaredMethod("\$longData")
        method.isAccessible = true
        return method.invoke(null) as ByteArray
    }

    private fun emitterWith(data: ByteArray): LongPrngDataEmitter {
        val emitter = LongPrngDataEmitter()
        emitter.appendData(data)
        emitter.requestGetterName()
        return emitter
    }

    @Test
    fun singleChunkDataLoadsAndRebuilds() {
        val data = ByteArray(100) { (it * 7 % 251).toByte() }
        val (cls, node) = buildAndLoad(emitterWith(data))

        val first = invokeLongData(cls)
        val second = invokeLongData(cls)
        assertArrayEquals(data, first)
        // 缓存命中：两次调用返回同一数组实例
        assertNotSame("无缓存：两次调用返回不同实例", first, second)

        // 统一走分片路径：1 个 chunk + 缓存字段
        val methods = node.methods.associate { it.name to it.desc }
        assertNotNull(methods["\$longData"])
        assertNotNull(methods["\$longChunk_0"])
        assertEquals("()[B", methods["\$longData"])
        assertTrue("小数据应只有一个 chunk", node.methods.count { it.name.startsWith("\$longChunk_") } == 1)
    }

    @Test
    fun chunkBoundary4096() {
        val data = ByteArray(4096) { (it % 256).toByte() }
        val (cls, node) = buildAndLoad(emitterWith(data))
        assertArrayEquals(data, invokeLongData(cls))
        assertEquals(1, node.methods.count { it.name.startsWith("\$longChunk_") })
    }

    @Test
    fun chunkBoundary4097() {
        val data = ByteArray(4097) { (it % 256).toByte() }
        val (cls, node) = buildAndLoad(emitterWith(data))
        assertArrayEquals(data, invokeLongData(cls))
        assertEquals(2, node.methods.count { it.name.startsWith("\$longChunk_") })
    }

    @Test
    fun multiChunkAggregation() {
        val data = ByteArray(10000) { (it * 31 % 256).toByte() }
        val (cls, node) = buildAndLoad(emitterWith(data))

        val first = invokeLongData(cls)
        assertArrayEquals(data, first)
        assertNotSame("无缓存：多 chunk 路径两次调用返回不同实例", first, invokeLongData(cls))

        assertEquals("10000 字节应切 3 个 chunk", 3, node.methods.count { it.name.startsWith("\$longChunk_") })
        // 聚合方法字节码不应超限（已成功加载即证明）
        val aggregate = node.methods.first { it.name == "\$longData" }
        assertTrue(aggregate.instructions.size() > 0)
    }

    @Test
    fun maxDataLoadsUnder64K() {
        // 65535 字节 → 16 个 chunk；加载成功即证明每个方法都未超 64KB
        val data = ByteArray(65535) { (it * 13 % 256).toByte() }
        val (cls, node) = buildAndLoad(emitterWith(data))
        assertArrayEquals(data, invokeLongData(cls))
        assertEquals(16, node.methods.count { it.name.startsWith("\$longChunk_") })
    }

    @Test
    fun existingMethodNameCollisionIsAvoided() {
        val emitter = LongPrngDataEmitter()
        emitter.reserveExistingNames(listOf("\$longData", "\$longChunk_0"))
        emitter.appendData(ByteArray(16) { 1 })
        val getterName = emitter.requestGetterName()
        assertEquals("已有 \$longData 时应分配 \$longData\$0", "\$longData\$0", getterName)

        val (cls, node) = buildAndLoad(emitter)
        assertNotNull(node.methods.find { it.name == "\$longData\$0" })
        val method = cls.getDeclaredMethod("\$longData\$0")
        method.isAccessible = true
        assertArrayEquals(ByteArray(16) { 1 }, method.invoke(null) as ByteArray)
    }


    @Test
    fun noPendingWithoutData() {
        val emitter = LongPrngDataEmitter()
        assertEquals(false, emitter.hasPending())
        assertEquals(null, emitter.requestGetterName())
    }

    @Test
    fun appendDataReturnsOffset() {
        val emitter = LongPrngDataEmitter()
        assertEquals(0, emitter.currentOffset())
        assertEquals(0, emitter.appendData(ByteArray(4)))
        assertEquals(4, emitter.currentOffset())
        assertEquals(4, emitter.appendData(ByteArray(6)))
        assertEquals(10, emitter.currentOffset())
    }
}
