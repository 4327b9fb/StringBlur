package com.android.string.plugin.util

import com.android.string.plugin.RecordingMethodVisitor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AsmWriter.writeLong 字节码序列测试：
 * ldc2_w(long) → invokestatic $longData()[B → invokestatic Wrapper.{methodName}Long(J[B)Ljava/lang/String;
 */
class AsmWriterLongTest {

    @Test
    fun writeLongEmitsExpectedSequence() {
        val mv = RecordingMethodVisitor()
        AsmWriter("com/example/Wrapper", "decrypt")
            .writeLong(123456789012345L, "\$longData", "com/example/Cls", mv)

        assertEquals(listOf<Any>(123456789012345L), mv.ldc)
        assertEquals(
            listOf(
                Triple("com/example/Cls", "\$longData", "()[B"),
                Triple("com/example/Wrapper", "decryptLong", "(J[B)Ljava/lang/String;")
            ),
            mv.calls
        )
    }

    @Test
    fun writeLongUsesConfiguredMethodNameSuffix() {
        val mv = RecordingMethodVisitor()
        AsmWriter("com/example/Wrapper", "myDecrypt")
            .writeLong(42L, "\$longData", "com/example/Cls", mv)

        assertEquals("myDecryptLong", mv.calls.last().second)
    }
}
