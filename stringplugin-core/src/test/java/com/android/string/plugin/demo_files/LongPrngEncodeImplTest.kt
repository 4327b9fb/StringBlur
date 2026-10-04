package com.android.string.plugin.demo_files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * LONG_PRNG 算法回环测试：加密（encryptWithData）→ 解密（decryptFromLong）。
 * 覆盖 ASCII / CJK / emoji（代理对）/ 单字符 / 边界长度 / 多偏移 / 种子随机性 / key 敏感性。
 */
class LongPrngEncodeImplTest {

    private val key = "test-key-2026"

    private fun roundTrip(data: String, offset: Int = 0): String {
        val result = LongPrngEncodeImpl.encryptWithData(data, key, offset)
        return LongPrngEncodeImpl().decryptFromLong(result.longValue, result.encryptedBytes, key)
    }

    @Test
    fun roundTripAscii() {
        assertEquals("hello world 123 !@#", roundTrip("hello world 123 !@#"))
    }

    @Test
    fun roundTripCjk() {
        assertEquals("你好，世界！这是一段中文", roundTrip("你好，世界！这是一段中文"))
    }

    @Test
    fun roundTripEmojiSurrogatePairs() {
        // emoji 占两个 char（代理对），必须完整还原
        assertEquals("🐱🐶🚀🎈", roundTrip("🐱🐶🚀🎈"))
    }

    @Test
    fun roundTripSingleChar() {
        assertEquals("a", roundTrip("a"))
        assertEquals("你", roundTrip("你"))
        assertEquals(" ", roundTrip(" "))
    }

    @Test
    fun roundTripMixed() {
        assertEquals("Hello 中文 🚀 mix#42 テスト", roundTrip("Hello 中文 🚀 mix#42 テスト"))
    }

    @Test
    fun roundTripLongString() {
        val longStr = "LongString测试".repeat(500)
        assertEquals(longStr, roundTrip(longStr))
    }

    @Test
    fun maxLengthBoundary() {
        // 实际可加密的最大长度受 16bit 数据偏移限制：32767 字符 × 2B = 65534 ≤ 65535
        assertEquals("a".repeat(32767), roundTrip("a".repeat(32767)))
    }

    @Test
    fun lengthLimit65535HitsDataOffsetLimit() {
        // 长度 65535 虽在 16bit 长度上限内，但 offset 0 + 131070B 超过 16bit 数据偏移上限
        assertThrows(IllegalStateException::class.java) {
            LongPrngEncodeImpl.encryptWithData("a".repeat(65535), key, 0)
        }
    }

    @Test
    fun differentOffsetsInSharedData() {
        val a = LongPrngEncodeImpl.encryptWithData("ab", key, 0)
        val b = LongPrngEncodeImpl.encryptWithData("cd", key, a.encryptedBytes.size)
        val data = a.encryptedBytes + b.encryptedBytes
        val impl = LongPrngEncodeImpl()
        assertEquals("ab", impl.decryptFromLong(a.longValue, data, key))
        assertEquals("cd", impl.decryptFromLong(b.longValue, data, key))
    }

    @Test
    fun samePlaintextGetsDifferentSeeds() {
        // 每串独立随机种子：相同明文两次加密，long 低32位（种子）应不同，高32位（长度/偏移）一致
        val mask = 0xFFFFFFFFL
        var seedA = 0L
        var seedB = 0L
        var attempts = 0
        do {
            seedA = LongPrngEncodeImpl.encryptWithData("same", key, 0).longValue and mask
            seedB = LongPrngEncodeImpl.encryptWithData("same", key, 0).longValue and mask
            attempts++
        } while (seedA == seedB && attempts < 50)
        assertNotEquals("相同明文应得到不同 PRNG 种子", seedA, seedB)

        val lenA = LongPrngEncodeImpl.encryptWithData("same", key, 0).longValue ushr 32
        val lenB = LongPrngEncodeImpl.encryptWithData("same", key, 0).longValue ushr 32
        assertEquals("长度位应一致", lenA, lenB)
    }

    @Test
    fun differentKeysProduceDifferentCiphertext() {
        val a = LongPrngEncodeImpl.encryptWithData("data", "keyA", 0).encryptedBytes
        val b = LongPrngEncodeImpl.encryptWithData("data", "keyB", 0).encryptedBytes
        assertFalse("不同 key 密文应不同", a.contentEquals(b))
    }

    @Test
    fun wrongKeyCannotDecrypt() {
        val result = LongPrngEncodeImpl.encryptWithData("secret", key, 0)
        val decrypted = LongPrngEncodeImpl().decryptFromLong(result.longValue, result.encryptedBytes, "wrong-key")
        assertNotEquals("错误 key 不应还原明文", "secret", decrypted)
    }

    @Test
    fun tamperedDataCannotDecrypt() {
        val result = LongPrngEncodeImpl.encryptWithData("secret", key, 0)
        val tampered = result.encryptedBytes.copyOf()
        tampered[0] = (tampered[0].toInt() xor 0x7F).toByte()
        val decrypted = LongPrngEncodeImpl().decryptFromLong(result.longValue, tampered, key)
        assertNotEquals("篡改密文不应还原明文", "secret", decrypted)
    }

    @Test
    fun offsetOverflowThrows() {
        // 32768 字符 → 65536 字节 > 65535（16bit offset 上限）
        assertThrows(IllegalStateException::class.java) {
            LongPrngEncodeImpl.encryptWithData("x".repeat(32768), key, 0)
        }
    }

    @Test
    fun emptyStringRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            LongPrngEncodeImpl.encryptWithData("", key, 0)
        }
    }

    @Test
    fun overMaxLengthRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            LongPrngEncodeImpl.encryptWithData("a".repeat(65536), key, 0)
        }
    }
}
