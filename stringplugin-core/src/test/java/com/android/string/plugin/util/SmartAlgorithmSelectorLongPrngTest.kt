package com.android.string.plugin.util

import com.android.string.plugin.mode.Mode
import com.android.string.plugin.mode.SelectionStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LONG_PRNG 在智能/安全选择策略下的表现测试。
 */
class SmartAlgorithmSelectorLongPrngTest {

    private val selector = SmartAlgorithmSelector()

    @Test
    fun securityStrategyPrefersLongPrng() {
        val modes = listOf(Mode.XOR, Mode.FAST_ROT, Mode.LONG_PRNG)
        assertEquals(Mode.LONG_PRNG, selector.selectBestAlgorithm("x", modes, SelectionStrategy.SECURITY))
    }

    @Test
    fun smartStrategyFavorsLongPrngForShortString() {
        val modes = listOf(Mode.XOR, Mode.FAST_ROT, Mode.LONG_PRNG, Mode.REVERSE)
        assertEquals(Mode.LONG_PRNG, selector.selectBestAlgorithm("a", modes, SelectionStrategy.SMART))
    }

    @Test
    fun smartStrategyKeepsLongPrngDominantForLongStrings() {
        // 默认权重下 LONG_PRNG 的安全分（1.5）足以抵消长字符串的长度劣势 → 仍选中
        val modes = listOf(Mode.XOR_SHIFT, Mode.LONG_PRNG)
        assertEquals(Mode.LONG_PRNG, selector.selectBestAlgorithm("x".repeat(1000), modes, SelectionStrategy.SMART))
    }

    @Test
    fun highPerformanceWeightPrefersReverseForLongStrings() {
        // 用户把性能权重调高、安全权重调低时，长字符串应选 REVERSE 而非 LONG_PRNG
        val modes = listOf(Mode.REVERSE, Mode.LONG_PRNG)
        assertEquals(
            Mode.REVERSE,
            selector.selectBestAlgorithm(
                "x".repeat(1000), modes, SelectionStrategy.SMART,
                performanceWeight = 0.7, securityWeight = 0.1
            )
        )
    }

    @Test
    fun singleModeShortCircuits() {
        assertEquals(Mode.LONG_PRNG, selector.selectBestAlgorithm("anything", listOf(Mode.LONG_PRNG), SelectionStrategy.SMART))
    }

    @Test
    fun descriptionAvailable() {
        val desc = SmartAlgorithmSelector.getModeDescription(Mode.LONG_PRNG)
        assertTrue(desc.contains("Long") || desc.contains("PRNG") || desc.contains("查找表"))
    }
}
