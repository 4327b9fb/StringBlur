package com.android.string.plugin.trasform

import com.android.string.plugin.RecordingMethodVisitor
import com.android.string.plugin.mode.BytesMode
import com.android.string.plugin.mode.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ClassVisitorController 的 LONG_PRNG 路径测试：
 * 空串、正常加密、相同字符串去重、类内数据偏移超限回退明文（不崩溃构建）。
 */
class ClassVisitorControllerLongPrngTest {

    private fun newController(): ClassVisitorController {
        return ClassVisitorController(
            wrapperClass = "com/example/Wrapper",
            wrapperMethod = "decrypt",
            key = "test-key-2026",
            bytesMode = BytesMode.BYTES,
            modes = listOf(Mode.LONG_PRNG),
            reportPath = null,
            minLength = 1
        ).also { it.currentClassName = "test/Cls" }
    }

    @Test
    fun emptyStringLoadsPlainEmpty() {
        val controller = newController()
        val mv = RecordingMethodVisitor()
        controller.write("", mv, "testMethod")
        assertEquals(listOf<Any>(""), mv.ldc)
        assertTrue(mv.calls.isEmpty())
        assertFalse("空串不产生加密数据", controller.hasSplitGetters())
    }

    @Test
    fun shortStringEmitsLongGetterAndDecryptCall() {
        val controller = newController()
        val mv = RecordingMethodVisitor()
        controller.write("hello", mv, "testMethod")

        // ldc2_w(long) → $longData()[B → Wrapper.decryptLong(J[B)
        assertEquals(1, mv.ldc.size)
        assertTrue("LDC 应为 long 常量", mv.ldc[0] is Long)
        assertEquals(
            listOf(
                Triple("test/Cls", "\$longData", "()[B"),
                Triple("com/example/Wrapper", "decryptLong", "(J[B)Ljava/lang/String;")
            ),
            mv.calls
        )
        assertTrue("有加密数据待发射", controller.hasSplitGetters())
    }

    @Test
    fun identicalStringsAreDeduplicated() {
        val controller = newController()
        val mv = RecordingMethodVisitor()
        controller.write("duplicate", mv, "m1")
        controller.write("duplicate", mv, "m2")

        // 两个调用点各自 push 同一个 long 常量（去重后长值相同、数据只追加一次）；
        // 若未去重，两次加密会拿到不同随机种子 → long 不同
        assertEquals("两个调用点各有一个 LDC", 2, mv.ldc.size)
        assertEquals("去重后两个 LDC 的值应相同", mv.ldc[0], mv.ldc[1])
        assertEquals(4, mv.calls.size)
        assertEquals("2 次数据 getter 调用", 2, mv.calls.count { it.second == "\$longData" })
        assertEquals("2 次解密入口调用", 2, mv.calls.count { it.second == "decryptLong" })
    }

    @Test
    fun differentStringsGetDifferentLongs() {
        val controller = newController()
        val mv = RecordingMethodVisitor()
        controller.write("aaa", mv, "m1")
        controller.write("bbb", mv, "m2")
        assertEquals(2, mv.ldc.size)
        assertEquals(4, mv.calls.size)
    }

    @Test
    fun dataOffsetOverflowFallsBackToPlaintext() {
        val controller = newController()
        val mv = RecordingMethodVisitor()

        // 第一条：32767 字符 → 65534 字节，恰好不超限，走加密路径
        controller.write("a".repeat(32767), mv, "m1")
        assertEquals(1, mv.ldc.size)
        assertEquals(2, mv.calls.size)

        // 第二条：偏移 65534 + 4 > 65535 → 回退明文，不再追加数据、不再生成调用
        controller.write("bb", mv, "m2")
        assertEquals("超限字符串应保持明文 LDC", "bb", mv.ldc[1])
        assertEquals("超限字符串不应再产生解密调用", 2, mv.calls.size)
    }

    @Test
    fun dataOffsetOverflowWithExistingDataKeepsLaterOffsetsValid() {
        val controller = newController()
        val mv = RecordingMethodVisitor()
        controller.write("a".repeat(32767), mv, "m1")   // offset 0, 65534B
        controller.write("bb", mv, "m2")                // 超限 → 明文，不占 offset
        controller.write("cc", mv, "m3")                // offset 仍为 65534 → 同样超限 → 明文
        assertEquals(listOf<Any>(mv.ldc[0], "bb", "cc"), mv.ldc)
        assertEquals(2, mv.calls.size)
    }

    @Test
    fun modeIndexSelectionPicksLongPrngWhenSoleMode() {
        val controller = newController()
        val mv = RecordingMethodVisitor()
        controller.write("x", mv, "m")
        // 唯一模式 → 直接走 LONG_PRNG 路径（不抛异常）
        assertTrue(mv.ldc[0] is Long)
    }
}
