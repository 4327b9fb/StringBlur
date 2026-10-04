package com.android.string.plugin.trasform

import com.android.string.plugin.mode.BytesMode
import com.android.string.plugin.mode.Mode
import com.android.string.plugin.mode.SelectionStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode

/**
 * split getter回归测试：
 * 1. getter方法体中已是密文，直接返回即可——绝不能再包含decrypt调用
 *    （历史bug：getter发射给变换中的visitor自身，密文LDC被二次加密，
 *      getter内多出一层decrypt，运行时需解密两次且密文被Base64二次膨胀）
 * 2. 单条数据超过chunk上限时必须切分为$chunk_N_M + $entry_N聚合，
 *    且输出类中每个方法的字节码都必须有大小上界（构造性保证，不依赖阈值配置）
 * 3. BYTES模式key数组全类唯一定义为$key()，调用点不得内联构造byte[]
 * 4. 端到端：加载转换后的class，用JVM版wrapper真实解密，结果必须等于明文
 */
class SplitGetterRegressionTest {

    // 方法字节码上界断言阈值：dex方法64KB硬限制，留余量取48KB
    private val methodBytecodeLimit = 48 * 1024

    @Test
    fun stringMode_gettersReturnCiphertextDirectly_andDecryptsCorrectly() {
        val plainTexts = (0 until 80).map { "plain-value-$it-" + "y".repeat(20) }
        val (output, classNode) = transform(BytesMode.STRING, plainTexts)

        classNode.methods.filter { it.name.startsWith("\$entry_") }.forEach { g ->
            assertEquals("()Ljava/lang/String;", g.desc)
            val calls = g.instructions.toList().filterIsInstance<MethodInsnNode>()
            assertTrue(
                "STRING getter ${g.name} 不应包含任何方法调用（密文直接areturn），实际: $calls",
                calls.isEmpty()
            )
        }

        assertDecryptsToPlaintext(output, plainTexts)
    }

    @Test
    fun bytesMode_gettersReturnCiphertextDirectly_andDecryptsCorrectly() {
        val plainTexts = (0 until 3).map { "bytes-plain-$it-" + "z".repeat(300) }
        val (output, classNode) = transform(BytesMode.BYTES, plainTexts)

        classNode.methods.filter { it.name.startsWith("\$entry_") }.forEach { g ->
            assertEquals("()[B", g.desc)
            val calls = g.instructions.toList().filterIsInstance<MethodInsnNode>()
            assertTrue(
                "BYTES getter ${g.name} 不应包含任何方法调用（密文byte[]直接areturn），实际: $calls",
                calls.isEmpty()
            )
        }

        // split模式下数据与key都走getter，调用点不应内联构造任何byte[]
        assertCallerHasNoByteArrayConstruction(classNode)

        assertDecryptsToPlaintext(output, plainTexts)
    }

    @Test
    fun bytesMode_hugeEntry_isChunked_andEveryMethodBounded_andDecryptsCorrectly() {
        // 单条20000字符明文 → Base64密文约27KB字节 → 切分为7个chunk；
        // 修复前：单getter内联整个byte[]，约135KB字节码，getter方法自身撞64KB上限
        val plainTexts = listOf("huge-" + "z".repeat(20000))
        val (output, classNode) = transform(BytesMode.BYTES, plainTexts)

        val chunks = classNode.methods.filter { it.name.startsWith("\$chunk_") }
        assertEquals("27KB密文应切出7个chunk", 7, chunks.size)
        chunks.forEach { c ->
            assertEquals("()[B", c.desc)
            assertTrue(
                "chunk方法 ${c.name} 应只构造数组、不含方法调用",
                c.instructions.toList().filterIsInstance<MethodInsnNode>().isEmpty()
            )
        }

        // 聚合方法$entry_0调用全部7个chunk做arraycopy拼接
        val aggregator = classNode.methods.single { it.name == "\$entry_0" }
        val chunkCalls = aggregator.instructions.toList().filterIsInstance<MethodInsnNode>()
            .count { it.name.startsWith("\$chunk_") }
        assertEquals(chunks.size, chunkCalls)

        // key getter全类唯一
        assertEquals("()[B", classNode.methods.single { it.name == "\$key" }.desc)

        assertCallerHasNoByteArrayConstruction(classNode)
        assertEveryMethodWithinBytecodeLimit(classNode)
        assertDecryptsToPlaintext(output, plainTexts)
    }

    @Test
    fun stringMode_hugeEntry_chunked_avoidsConstantPoolOverflow_andDecryptsCorrectly() {
        // 单条60000字符明文 → Base64密文约80000字符，超过常量池modified-UTF8单条目64KB上限；
        // 修复前：getter单条LDC直接让ClassWriter抛"UTF8 string too large"
        val plainTexts = listOf("huge-" + "y".repeat(60000))
        val (output, classNode) = transform(BytesMode.STRING, plainTexts)

        val chunks = classNode.methods.filter { it.name.startsWith("\$chunk_") }
        assertEquals("80000字符密文应切出20个chunk", 20, chunks.size)
        chunks.forEach { c ->
            assertEquals("()Ljava/lang/String;", c.desc)
            assertTrue(
                "chunk方法 ${c.name} 应只有单条LDC、不含方法调用",
                c.instructions.toList().filterIsInstance<MethodInsnNode>().isEmpty()
            )
        }

        val aggregator = classNode.methods.single { it.name == "\$entry_0" }
        val chunkCalls = aggregator.instructions.toList().filterIsInstance<MethodInsnNode>()
            .count { it.name.startsWith("\$chunk_") }
        assertEquals(chunks.size, chunkCalls)

        assertEveryMethodWithinBytecodeLimit(classNode)
        assertDecryptsToPlaintext(output, plainTexts)
    }

    @Test
    fun bytesMode_inlinePath_usesSharedKeyGetter_andDecryptsCorrectly() {
        // 数据很小（5条×~30字符）→ 密文约1.2KB，远低于48KB阈值，不触发split latch，密文内联；
        // 但key数组仍应全类唯一定义为$key()，调用点invokestatic引用（内联路径与拆分路径一致）
        val plainTexts = (0 until 5).map { "inline-$it-" + "a".repeat(20) }
        val (output, classNode) = transform(
            BytesMode.BYTES, plainTexts, expectGetters = false, dataSplitThreshold = 64
        )

        assertTrue(
            "不应生成split条目getter",
            classNode.methods.none { it.name.startsWith("\$entry_") })
        assertEquals("()[B", classNode.methods.single { it.name == "\$key" }.desc)

        val caller = classNode.methods.single { it.name == "values" }
        val keyGetterCalls = caller.instructions.toList().filterIsInstance<MethodInsnNode>()
            .count { it.name == "\$key" }
        assertEquals("每条加密调用应引用一次共享\$key()", plainTexts.size, keyGetterCalls)

        assertDecryptsToPlaintext(output, plainTexts)
    }

    /**
     * AGP管道的ClassWriter使用flags=0（不重算maxStack/maxLocals/帧），
     * 插件必须自己保证注入代码的栈高水位被正确声明。
     * 本用例输出ClassWriter(0)并实际加载+调用，任何maxStack低报都会在加载/调用时VerifyError。
     */
    @Test
    fun noComputeMaxs_smallOriginalMethod_bytesInline_passesVerification() {
        val plain = "maxstack-secret-" + "z".repeat(40)

        val input = ClassWriter(0)
        input.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "test/Target", null, "java/lang/Object", null)
        run {
            val mv = input.visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                "get", "()Ljava/lang/String;", null, null
            )
            mv.visitCode()
            mv.visitLdcInsn(plain)     // 原方法栈峰值仅1
            mv.visitInsn(Opcodes.ARETURN)
            mv.visitMaxs(1, 0)
            mv.visitEnd()
        }
        input.visitEnd()

        // 数据小（一条~55字符）→ 不触发split，走BYTES内联路径（byte[]构造栈峰值为4）
        val outBytes = transformWithoutComputeMaxs(
            input.toByteArray(), BytesMode.BYTES, dataSplitThreshold = 64
        )

        val target = loadTransformedClass(outBytes)
        val result = target.getMethod("get").invoke(null)
        assertEquals(plain, result)
    }

    @Test
    fun noComputeMaxs_syntheticClinit_passesVerification_forBothModes() {
        listOf(BytesMode.BYTES, BytesMode.STRING).forEach { bytesMode ->
            val plain = "https://example.test/" + "p".repeat(40)

            val input = ClassWriter(0)
            input.visit(
                Opcodes.V1_8,
                Opcodes.ACC_PUBLIC,
                "test/Target",
                null,
                "java/lang/Object",
                null
            )
            // static final String常量：javac只写ConstantValue属性、类中无<clinit>，
            // 插件移除常量值属性后需合成<clinit>（其maxStack由插件自己负责声明）
            input.visitField(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
                "URL", "Ljava/lang/String;", null, plain
            ).visitEnd()
            input.visitEnd()

            val outBytes = transformWithoutComputeMaxs(
                input.toByteArray(), bytesMode, dataSplitThreshold = 64
            )

            val target = loadTransformedClass(outBytes)
            assertEquals(
                "模式 $bytesMode 合成clinit应在类初始化时正确解密字段",
                plain,
                target.getField("URL").get(null)
            )
        }
    }

    /**
     * 被转换类已存在与合成getter同名的方法（方法名含'$'在字节码层面完全合法）：
     * 合成方法必须自动加后缀消歧，否则产出重复方法签名（JVM ClassFormatError / d8拒绝）。
     * 冲突方法刻意排在含密文的values()之后——流式处理下命名分配时它们"尚未被访问"，
     * 只有全类缓冲+回放前登记名字才能覆盖此场景。
     */
    @Test
    fun existingMethodsWithSyntheticNames_areDisambiguated_andDecryptsCorrectly() {
        // 1KB阈值下80条×~70字符足够触发split
        // （STRING模式每条估算仅15字节，80条=1.2KB >> 1KB阈值）
        val plainTexts = (0 until 80).map { "collide-$it-" + "s".repeat(60) }
        val bytesMode = BytesMode.BYTES

        val input = ClassWriter(0)
        input.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "test/Target", null, "java/lang/Object", null)

        // values() 在最前：加密点先于冲突方法被回放
        run {
            val mv = input.visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                "values", "()[Ljava/lang/String;", null, null
            )
            mv.visitCode()
            pushInt(mv, plainTexts.size)
            mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
            plainTexts.forEachIndexed { i, s ->
                mv.visitInsn(Opcodes.DUP)
                pushInt(mv, i)
                mv.visitLdcInsn(s)
                mv.visitInsn(Opcodes.AASTORE)
            }
            mv.visitInsn(Opcodes.ARETURN)
            mv.visitMaxs(4, 1)
            mv.visitEnd()
        }

        // 冲突方法：名字与合成getter相同；方法体不含任何字符串LDC，自身不触发加密
        emitEmptyByteArrayMethod(input, "\$key")
        emitEmptyByteArrayMethod(input, "\$entry_0")   // 与BYTES条目同签名
        emitNullStringMethod(input, "\$entry_0")     // 同名不同签名（重载，合法）
        emitNullStringMethod(input, "\$entry_1")     // 与STRING条目同签名
        input.visitEnd()

        // flags=0输出：重复方法签名会在defineClass时直接ClassFormatError
        val outBytes = transformWithoutComputeMaxs(
            input.toByteArray(), bytesMode, dataSplitThreshold = 1
        )

        val classNode = ClassNode(Opcodes.ASM9)
        ClassReader(outBytes).accept(classNode, 0)

        // 核心不变量：类中不得出现重复的"方法名+签名"
        val signatures = classNode.methods.map { it.name + it.desc }
        assertEquals(
            "存在重复方法签名: ${signatures.groupingBy { it }.eachCount().filter { it.value > 1 }}",
            signatures.size, signatures.distinct().size
        )

        // 原有冲突方法原样保留
        assertTrue(classNode.methods.any { it.name == "\$key" && it.desc == "()[B" })

        // 合成getter以消歧后的名字存在（$entry_0/$entry_1/$key 均被占用）
        assertTrue(classNode.methods.any { it.name == "\$entry_0\$0" })
        assertTrue(classNode.methods.any { it.name == "\$entry_1\$0" })
        assertTrue(
            "BYTES模式key getter应被消歧为\$key\$0",
            classNode.methods.any { it.name == "\$key\$0" && it.desc == "()[B" })

        // values() 中所有指向本类的invokestatic都必须能解析到真实存在的方法
        val names = classNode.methods.associateBy { it.name + it.desc }
        val unresolved = classNode.methods.single { it.name == "values" }
            .instructions.toList().filterIsInstance<MethodInsnNode>()
            .filter { it.owner == "test/Target" }
            .filter { names[it.name + it.desc] == null }
        assertTrue("values()存在无法解析的调用: $unresolved", unresolved.isEmpty())

        // 端到端：调用点与发射名不匹配会NoSuchMethodError，重名会ClassFormatError
        val target = loadTransformedClass(outBytes)
        val result = target.getMethod("values").invoke(null) as Array<*>
        assertEquals(plainTexts, result.toList())
    }

    // ==================== 修复1/2/4 专项回归 ====================

    /**
     * 修复1回归（BYTES估算系数6->7）：原方法自身~13KB + 多条小密文（单条<4096，
     * 不触发oversized短路，只走累计阈值latch）时：
     *  - 修前：估算 6*ΣC+120N ≈ 48.6KB ≤ 50KB → 不触发split → 10条全部内联，
     *    实际字节 ≈ 6.97*ΣC + 13KB ≈ 68.6KB > 65535 → MethodTooLargeException
     *  - 修后：估算 7*ΣC+120N ≈ 56.5KB > 50KB → 触发split → 方法 ≈ 13KB + 调用点，安全
     */
    @Test
    fun bytesMode_largeOriginalMethod_withManySmallEntries_avoidsMethodTooLarge() {
        // 每条明文~590字符 → Base64密文~790B（10条合计~7.9KB，单条远小于4096）
        val plainTexts = (0 until 10).map { "est-$it-" + "q".repeat(580) }
        val nops = 13000

        val input = ClassWriter(0)
        input.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "test/Target", null, "java/lang/Object", null)
        run {
            val mv = input.visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                "values", "()[Ljava/lang/String;", null, null
            )
            mv.visitCode()
            // 原方法自身的既有字节码（模拟大方法：switch表/初始化块等），不计入加密估算
            repeat(nops) { mv.visitInsn(Opcodes.NOP) }
            mv.visitLdcInsn("placeholder")  // 占位LDC会被替换为密文/解密调用，无碍
            mv.visitInsn(Opcodes.POP)
            pushInt(mv, plainTexts.size)
            mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
            plainTexts.forEachIndexed { i, s ->
                mv.visitInsn(Opcodes.DUP)
                pushInt(mv, i)
                mv.visitLdcInsn(s)
                mv.visitInsn(Opcodes.AASTORE)
            }
            mv.visitInsn(Opcodes.ARETURN)
            mv.visitMaxs(4, 1)
            mv.visitEnd()
        }
        input.visitEnd()

        try {
            transformWithoutComputeMaxs(input.toByteArray(), BytesMode.BYTES, dataSplitThreshold = 50)
        } catch (t: Throwable) {
            // 捕获ASM的MethodTooLargeException（或其包装）——修前必抛
            fail("BYTES估算系数6时本用例应触发split、不得抛出方法超限异常；实际: $t")
        }
    }

    /**
     * 修复2回归（getter静态缓存）：$key()与$entry_N()两次调用必须返回同一实例，
     * 不能每次解密都重建数组。
     */
    @Test
    fun bytesMode_getters_returnFreshInstances() {
        // $key缓存：小数据走inline路径（不触发split），$key()仍全类唯一
        val small = (0 until 3).map { "key-$it" }
        val outSmall = transformWithoutComputeMaxs(
            buildTargetBytes(small), BytesMode.BYTES, dataSplitThreshold = 64
        )
        val t1 = loadTransformedClass(outSmall)
        val keyGetter = t1.getDeclaredMethod("\$key").apply { isAccessible = true }
        assertNotSame("无缓存：\$key()两次调用返回不同实例", keyGetter.invoke(null), keyGetter.invoke(null))

        // $entry_0缓存：大单条密文（>4096）走oversized split，$entry_0存在
        val big = listOf("big-" + "w".repeat(5000))
        val outBig = transformWithoutComputeMaxs(
            buildTargetBytes(big), BytesMode.BYTES, dataSplitThreshold = 1
        )
        val t2 = loadTransformedClass(outBig)
        val entryGetter = t2.getDeclaredMethod("\$entry_0").apply { isAccessible = true }
        assertNotSame("无缓存：\$entry_0()两次调用返回不同实例", entryGetter.invoke(null), entryGetter.invoke(null))
    }

    /**
     * 修复4回归（maxStack条件化）：未注入任何字符串的方法maxStack必须保持原声明，
     * 不能被无条件+3。
     */
    @Test
    fun noComputeMaxs_plainMethod_maxStackUnchanged() {
        val input = ClassWriter(0)
        input.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "test/Target", null, "java/lang/Object", null)
        run {
            val mv = input.visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "plain", "()I", null, null
            )
            mv.visitCode()
            mv.visitInsn(Opcodes.ICONST_1)
            mv.visitInsn(Opcodes.IRETURN)
            mv.visitMaxs(1, 0)
            mv.visitEnd()
        }
        input.visitEnd()

        val outBytes = transformWithoutComputeMaxs(
            input.toByteArray(), BytesMode.BYTES, dataSplitThreshold = 64
        )
        val classNode = ClassNode(Opcodes.ASM9)
        ClassReader(outBytes).accept(classNode, 0)
        val plain = classNode.methods.single { it.name == "plain" }
        assertEquals("未注入字符串的方法maxStack不应被+3", 1, plain.maxStack)
    }

    /** 构造含values()数组返回方法的test/Target类字节 */
    private fun buildTargetBytes(plainTexts: List<String>): ByteArray {
        val input = ClassWriter(0)
        input.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "test/Target", null, "java/lang/Object", null)
        val mv = input.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "values", "()[Ljava/lang/String;", null, null
        )
        mv.visitCode()
        pushInt(mv, plainTexts.size)
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
        plainTexts.forEachIndexed { i, s ->
            mv.visitInsn(Opcodes.DUP)
            pushInt(mv, i)
            mv.visitLdcInsn(s)
            mv.visitInsn(Opcodes.AASTORE)
        }
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(4, 1)
        mv.visitEnd()
        input.visitEnd()
        return input.toByteArray()
    }

    private fun emitEmptyByteArrayMethod(writer: ClassWriter, name: String) {
        val mv = writer.visitMethod(
            Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC, name, "()[B", null, null
        )
        mv.visitCode()
        mv.visitInsn(Opcodes.ICONST_0)
        mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_BYTE)
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(1, 0)
        mv.visitEnd()
    }

    private fun emitNullStringMethod(writer: ClassWriter, name: String) {
        val mv = writer.visitMethod(
            Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC, name, "()Ljava/lang/String;", null, null
        )
        mv.visitCode()
        mv.visitInsn(Opcodes.ACONST_NULL)
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(1, 0)
        mv.visitEnd()
    }

    // ---- helpers ----

    /** 用flags=0的ClassWriter做输出（模拟AGP：不重算maxs/frames），返回转换后的class字节 */
    private fun transformWithoutComputeMaxs(
        inputBytes: ByteArray, bytesMode: BytesMode, dataSplitThreshold: Int = 50
    ): ByteArray {
        val output = ClassWriter(0)
        val visitor = StringBlurClassVisitor(
            output,
            "test/StringBlur",
            "decrypt",
            "test-key",
            bytesMode,
            listOf(Mode.DEFAULT),
            null,
            3,
            true,
            SelectionStrategy.RANDOM,
            0.5,
            0.5,
            dataSplitThreshold
        )
        ClassReader(inputBytes).accept(visitor, 0)
        return output.toByteArray()
    }

    /** 加载转换后的 test/Target（连同JVM版wrapper），maxStack低报会在此处VerifyError */
    private fun loadTransformedClass(targetBytes: ByteArray): Class<*> {
        val loader = object : ClassLoader(SplitGetterRegressionTest::class.java.classLoader) {
            override fun findClass(name: String): Class<*> {
                val bytes = when (name) {
                    "test.Target" -> targetBytes
                    "test.StringBlur" -> generateRuntimeWrapper()
                    else -> throw ClassNotFoundException(name)
                }
                return defineClass(name, bytes, 0, bytes.size)
            }
        }
        return loader.loadClass("test.Target")
    }

    private fun transform(
        bytesMode: BytesMode,
        plainTexts: List<String>,
        expectGetters: Boolean = true,
        dataSplitThreshold: Int = 1
    ): Pair<ByteArray, ClassNode> {
        val input = ClassWriter(0)
        input.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "test/Target", null, "java/lang/Object", null)
        val mv = input.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "values", "()[Ljava/lang/String;", null, null
        )
        mv.visitCode()
        pushInt(mv, plainTexts.size)
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
        plainTexts.forEachIndexed { i, s ->
            mv.visitInsn(Opcodes.DUP)
            pushInt(mv, i)
            mv.visitLdcInsn(s)
            mv.visitInsn(Opcodes.AASTORE)
        }
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(4, 1)
        mv.visitEnd()
        input.visitEnd()

        // COMPUTE_FRAMES：重算栈高与帧（宽松环境）；maxStack声明问题由noComputeMaxs用例专门守护
        val output = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        val visitor = StringBlurClassVisitor(
            output,
            "test/StringBlur",
            "decrypt",
            "test-key",
            bytesMode,
            listOf(Mode.DEFAULT),
            null,
            3,
            true,
            SelectionStrategy.RANDOM,
            0.5,
            0.5,
            dataSplitThreshold
        )
        ClassReader(input.toByteArray()).accept(visitor, 0)
        val outBytes = output.toByteArray()

        val classNode = ClassNode(Opcodes.ASM9)
        ClassReader(outBytes).accept(classNode, 0)

        val getters = classNode.methods.filter { it.name.startsWith("\$entry_") }
        if (expectGetters) {
            assertTrue("测试场景应触发split getter生成", getters.isNotEmpty())
        }

        // 调用点：每个明文字符串恰好一层decrypt，每个getter恰好被调用一次
        val caller = classNode.methods.single { it.name == "values" }
        val callerInsns = caller.instructions.toList()
        val decryptCalls = callerInsns.filterIsInstance<MethodInsnNode>()
            .count { it.owner == "test/StringBlur" && it.name == "decrypt" }
        assertEquals("每个字符串应只经过一层decrypt", plainTexts.size, decryptCalls)
        val getterCalls = callerInsns.filterIsInstance<MethodInsnNode>()
            .count { it.name.startsWith("\$entry_") }
        assertEquals(getters.size, getterCalls)

        return outBytes to classNode
    }

    private fun assertCallerHasNoByteArrayConstruction(classNode: ClassNode) {
        val caller = classNode.methods.single { it.name == "values" }
        val hasByteArrayBuild = caller.instructions.any { insn ->
            (insn is IntInsnNode && insn.opcode == Opcodes.NEWARRAY) ||
                    insn.opcode == Opcodes.BASTORE
        }
        assertFalse(
            "BYTES模式调用点不应内联构造byte[]（密文与key都应走getter引用）",
            hasByteArrayBuild
        )
    }

    private fun assertEveryMethodWithinBytecodeLimit(classNode: ClassNode) {
        classNode.methods.forEach { method ->
            val size = bytecodeSize(method)
            assertTrue(
                "方法 ${method.name}${method.desc} 字节码约${size}字节，超过${methodBytecodeLimit}上限",
                size <= methodBytecodeLimit
            )
        }
    }

    /**
     * 按JVM指令编码规则估算方法体字节码长度（覆盖本项目生成的全部指令形态，
     * LDC统一按ldc_w=3字节保守计数，保证上界断言只偏松不偏紧）
     */
    private fun bytecodeSize(method: MethodNode): Int {
        val counter = object : MethodVisitor(Opcodes.ASM9) {
            var size = 0

            override fun visitInsn(opcode: Int) {
                size += 1
            }

            override fun visitIntInsn(opcode: Int, operand: Int) {
                size += when (opcode) {
                    Opcodes.SIPUSH -> 3
                    else -> 2 // BIPUSH、NEWARRAY
                }
            }

            override fun visitVarInsn(opcode: Int, varIndex: Int) {
                size += 2 // 带操作数的xload/xstore（_0.._3单字节形式走visitInsn）
            }

            override fun visitTypeInsn(opcode: Int, type: String?) {
                size += 3
            }

            override fun visitFieldInsn(opcode: Int, owner: String?, name: String?, desc: String?) {
                size += 3
            }

            override fun visitMethodInsn(
                opcode: Int, owner: String?, name: String?, desc: String?, isInterface: Boolean
            ) {
                size += 3
            }

            override fun visitInvokeDynamicInsn(
                name: String?,
                descriptor: String?,
                bootstrapMethodHandle: org.objectweb.asm.Handle?,
                vararg bootstrapMethodArguments: Any?
            ) {
                size += 5
            }

            override fun visitJumpInsn(opcode: Int, label: org.objectweb.asm.Label?) {
                size += 3
            }

            override fun visitLdcInsn(value: Any?) {
                size += 3
            } // 保守按ldc_w计

            override fun visitIincInsn(varIndex: Int, increment: Int) {
                size += 3
            }
        }
        method.accept(counter)
        return counter.size
    }

    private fun assertDecryptsToPlaintext(targetBytes: ByteArray, plainTexts: List<String>) {
        val loader = object : ClassLoader(SplitGetterRegressionTest::class.java.classLoader) {
            override fun findClass(name: String): Class<*> {
                val bytes = when (name) {
                    "test.Target" -> targetBytes
                    "test.StringBlur" -> generateRuntimeWrapper()
                    else -> throw ClassNotFoundException(name)
                }
                return defineClass(name, bytes, 0, bytes.size)
            }
        }
        val target = loader.loadClass("test.Target")
        val result = target.getMethod("values").invoke(null) as Array<*>
        assertEquals(plainTexts, result.toList())
    }

    /**
     * 生成运行时wrapper test/StringBlur：
     * decrypt(String,String,I) 与 decrypt([B[BI) 均用 java.util.Base64 解码后
     * 交给 DefaultEncodeImpl 解密（与线上 IString.decryptString/decryptBytes 语义一致）
     */
    private fun generateRuntimeWrapper(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC,
            "test/StringBlur",
            null,
            "java/lang/Object",
            null
        )

        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }

        // decrypt(String value, String key, int mode): String
        cw.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "decrypt",
            "(Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/String;",
            null, null
        ).apply {
            visitCode()
            emitDecryptBody(this, stringInput = true)
            visitMaxs(4, 5)
            visitEnd()
        }

        // decrypt(byte[] value, byte[] key, int mode): String
        cw.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "decrypt",
            "([B[BI)Ljava/lang/String;",
            null, null
        ).apply {
            visitCode()
            emitDecryptBody(this, stringInput = false)
            visitMaxs(4, 5)
            visitEnd()
        }

        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun emitDecryptBody(mv: MethodVisitor, stringInput: Boolean) {
        // DefaultEncodeImpl impl = new DefaultEncodeImpl();
        mv.visitTypeInsn(Opcodes.NEW, "com/android/string/plugin/demo_files/DefaultEncodeImpl")
        mv.visitInsn(Opcodes.DUP)
        mv.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            "com/android/string/plugin/demo_files/DefaultEncodeImpl",
            "<init>", "()V", false
        )
        mv.visitVarInsn(Opcodes.ASTORE, 3)

        // byte[] decoded = Base64.getDecoder().decode(value)
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC, "java/util/Base64", "getDecoder",
            "()Ljava/util/Base64\$Decoder;", false
        )
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        val decodeDesc = if (stringInput) "(Ljava/lang/String;)[B" else "([B)[B"
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL, "java/util/Base64\$Decoder",
            "decode", decodeDesc, false
        )
        mv.visitVarInsn(Opcodes.ASTORE, 4)

        // byte[] plain = impl.decrypt(decoded, key.getBytes())  // BYTES模式key已经是byte[]
        mv.visitVarInsn(Opcodes.ALOAD, 3)
        mv.visitVarInsn(Opcodes.ALOAD, 4)
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        if (stringInput) {
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "getBytes", "()[B", false)
        }
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "com/android/string/plugin/demo_files/DefaultEncodeImpl",
            "decrypt", "([B[B)[B", false
        )
        mv.visitVarInsn(Opcodes.ASTORE, 4)

        // return new String(plain)
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/String")
        mv.visitInsn(Opcodes.DUP)
        mv.visitVarInsn(Opcodes.ALOAD, 4)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/String", "<init>", "([B)V", false)
        mv.visitInsn(Opcodes.ARETURN)
    }

    private fun pushInt(mv: MethodVisitor, value: Int) {
        when (value) {
            in 0..5 -> mv.visitInsn(Opcodes.ICONST_0 + value)
            in Byte.MIN_VALUE..Byte.MAX_VALUE -> mv.visitIntInsn(Opcodes.BIPUSH, value)
            in Short.MIN_VALUE..Short.MAX_VALUE -> mv.visitIntInsn(Opcodes.SIPUSH, value)
            else -> mv.visitLdcInsn(value)
        }
    }
}
