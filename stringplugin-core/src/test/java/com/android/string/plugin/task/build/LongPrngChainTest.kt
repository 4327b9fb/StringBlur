package com.android.string.plugin.task.build

import com.android.string.plugin.demo_files.LongPrngEncodeImpl
import com.android.string.plugin.mode.Mode
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.net.URLClassLoader
import javax.tools.ToolProvider

/**
 * 全链路测试（模拟插件运行时）：
 * 1. StringBlurFile 生成 wrapper（含 decryptLong(J[B) 入口）
 * 2. LongPrngEncodeImplFile 生成运行时 impl（内嵌 key，提供 decryptLong）
 * 3. javac 编译生成源码（类路径含 stringplugin-common 的 IString）
 * 4. 加载并调用 wrapper.decryptLong(long, byte[]) → 还原明文
 *
 * 该测试验证了「wrapper 调用链 + key 内嵌 + 查找表一致性」整条运行时链路。
 */
class LongPrngChainTest {

    @Test
    fun wrapperDecryptLongRestoresPlaintext() {
        val key = "chain-test-key-2026"
        val appId = "com.example.app"
        val modes = listOf(Mode.LONG_PRNG)

        val tmp = createTempDir("stringblur-chain")
        val genDir = File(tmp, "gen").apply { mkdirs() }
        val outDir = File(tmp, "out").apply { mkdirs() }

        // 1. 生成 wrapper 与运行时 impl
        StringBlurFile().createEntry(genDir, appId, modes, "StringBlur", "decrypt")
        LongPrngEncodeImplFile().create(genDir, appId, modes, key)

        // 2. 编译生成源码
        val sources = genDir.walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .toList()
        check(sources.isNotEmpty()) { "generated sources missing" }
        val compiler = ToolProvider.getSystemJavaCompiler() ?: error("JDK javac unavailable")
        val fileManager = compiler.getStandardFileManager(null, null, null)
        val task = compiler.getTask(
            null,
            fileManager,
            null,
            listOf("-classpath", System.getProperty("java.class.path"), "-d", outDir.absolutePath),
            null,
            fileManager.getJavaFileObjectsFromFiles(sources)
        )
        check(task.call()) { "generated sources failed to compile" }
        fileManager.close()

        // 3. 加载 wrapper
        val loader = URLClassLoader(arrayOf(outDir.toURI().toURL()), this.javaClass.classLoader)
        val wrapper = loader.loadClass("$appId.stringblur.StringBlur")
        val decryptLong = wrapper.getMethod(
            "decryptLong",
            Long::class.javaPrimitiveType,
            ByteArray::class.java
        )

        // 4. 用 demo 实现加密（同一 key、同一算法），经 wrapper 解密还原
        val plain1 = "你好，StringBlur 🚀 2026 chain"
        val r1 = LongPrngEncodeImpl.encryptWithData(plain1, key, 0)
        assertEquals(plain1, decryptLong.invoke(null, r1.longValue, r1.encryptedBytes))

        // 5. 多字符串共享数据缓冲、不同偏移
        val plain2 = "second-string-第二串"
        val r2 = LongPrngEncodeImpl.encryptWithData(plain2, key, r1.encryptedBytes.size)
        val allData = r1.encryptedBytes + r2.encryptedBytes
        assertEquals(plain1, decryptLong.invoke(null, r1.longValue, allData))
        assertEquals(plain2, decryptLong.invoke(null, r2.longValue, allData))
    }

    @Test
    fun generatedImplEmbedsKey() {
        val key = "embed-me-42"
        val appId = "com.example.embed"
        val tmp = createTempDir("stringblur-embed")
        val genDir = File(tmp, "gen").apply { mkdirs() }
        val outDir = File(tmp, "out").apply { mkdirs() }

        LongPrngEncodeImplFile().create(genDir, appId, listOf(Mode.LONG_PRNG), key)
        val sources = genDir.walkTopDown().filter { it.isFile && it.extension == "java" }.toList()
        check(sources.isNotEmpty())
        val compiler = ToolProvider.getSystemJavaCompiler() ?: error("JDK javac unavailable")
        val fileManager = compiler.getStandardFileManager(null, null, null)
        val task = compiler.getTask(
            null, fileManager, null,
            listOf("-classpath", System.getProperty("java.class.path"), "-d", outDir.absolutePath),
            null, fileManager.getJavaFileObjectsFromFiles(sources)
        )
        check(task.call())
        fileManager.close()

        val loader = URLClassLoader(arrayOf(outDir.toURI().toURL()), this.javaClass.classLoader)
        val impl = loader.loadClass("$appId.stringblur.LongPrngEncodeImpl")
        // key 不进 APK：运行时类内嵌的是编译期由 key 派生的查找表种子（64 位常量）
        val seedField = impl.getDeclaredField("TABLE_SEED")
        seedField.isAccessible = true
        assertEquals(
            "内嵌 TABLE_SEED 应等于编译期 deriveTableSeed(key)",
            com.android.string.plugin.demo_files.LongPrngEncodeImpl.deriveTableSeed(key),
            seedField.get(null)
        )

        // 内嵌种子的 decryptLong 与 demo 加密互通
        val plain = "cross-check"
        val result = LongPrngEncodeImpl.encryptWithData(plain, key, 0)
        val decryptLong = impl.getMethod("decryptLong", Long::class.javaPrimitiveType, ByteArray::class.java)
        val instance = impl.getDeclaredConstructor().newInstance()
        assertEquals(plain, decryptLong.invoke(instance, result.longValue, result.encryptedBytes))
    }
}
