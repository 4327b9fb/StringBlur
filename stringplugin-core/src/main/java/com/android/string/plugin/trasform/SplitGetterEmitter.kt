package com.android.string.plugin.trasform

import com.android.string.plugin.trasform.SplitGetterEmitter.Companion.CHUNK_SIZE
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * 数据拆分的getter方法发射器
 * 用于解决Dex方法大小超过64KB限制的问题：
 * 当方法中加密数据导致字节码超过阈值时，将数据提取为当前类的静态getter方法，
 * 原方法体中以invokestatic调用替代内联数据。
 *
 * 每个被转换的类持有一个独立实例（由ClassVisitorController创建），
 * 类处理完成后随visitor一起被GC回收，不保留任何跨类/跨构建的状态，
 * 因此不存在异常中断后的状态残留，并行处理各类时也互不干扰。
 *
 * 生成的方法（均为private static synthetic，直接发射到下游visitor，
 * 不经过本类的字符串加密变换——方法体内已是密文）：
 *  - $key()：BYTES模式的key字节数组，全类唯一定义一次，所有调用点共享，
 *    避免每个调用点重复内联构造key数组（"单一定义+统一引用"）；
 *  - $entry_N()：第N条超限数据的getter，调用点协议固定为invokestatic；
 *  - $chunk_N_M()：当单条数据超过[CHUNK_SIZE]时，数据被切分为多个chunk，
 *    每个chunk一个getter，$entry_N()负责聚合（byte[]用System.arraycopy拼接，
 *    String用StringBuilder拼接）。
 *
 * 命名冲突处理：被转换类中若已存在同名方法（JVM允许方法名含'$'，
 * Kotlin/其他插桩也可能生成），合成方法会自动加'$N'后缀消歧（$key$0、
 * $entry_0$0、$chunk_0_0$0……），避免产出重复方法签名导致ClassFormatError。
 * 因此调用点必须始终使用[registerEntry]/[requestKeyGetter]返回的名字，
 * 不能硬编码前缀拼名。
 *
 * 大小上界（构造性保证，不依赖阈值配置）：
 *  - chunk getter：≤CHUNK_SIZE个数据单元，byte[]填充最坏约8字节/单元 → ≤~33KB字节码；
 *    String chunk为单条LDC，≤CHUNK_SIZE字符，远低于常量池64KB上限；
 *  - 聚合方法：每chunk固定约10字节（arraycopy）/7字节（append），
 *    单条数据约14MB以内聚合方法自身不会超限；
 *  - $key()：key通常很短，同理有界。
 */
class SplitGetterEmitter {

    // 已注册、待发射getter方法的条目；条目顺序即getter序号
    private val pendingEntries = mutableListOf<SplitGetterEntry>()

    // key getter全类只生成一次；null表示尚未请求
    private var keyBytes: ByteArray? = null
    private var keyGetterName: String? = null

    // 已占用的方法名：被转换类原有方法 + 本emitter已分配的合成名
    private val takenNames = HashSet<String>()

    /**
     * 登记被转换类中已存在的方法名，必须在任何registerEntry/requestKeyGetter
     * 调用之前（即方法体回放之前）完成，保证分配出的合成名与原有方法不重名
     */
    fun reserveExistingNames(names: Collection<String>) {
        takenNames.addAll(names)
    }

    /**
     * 分配合成方法名：优先使用preferred；被占用时依次尝试preferred$0、preferred$1……
     */
    private fun allocateName(preferred: String): String {
        if (takenNames.add(preferred)) {
            return preferred
        }
        var counter = 0
        while (true) {
            val candidate = "$preferred\$$counter"
            if (takenNames.add(candidate)) {
                return candidate
            }
            counter++
        }
    }

    /**
     * 注册一条超限加密数据，返回其getter方法名，供方法体生成invokestatic指令
     * @param data 加密后的数据（String 或 ByteArray）
     * @param isBytesMode 是否为BYTES模式（决定getter返回类型）
     */
    fun registerEntry(data: Any, isBytesMode: Boolean): String {
        val getterName = allocateName(entryGetterName(pendingEntries.size))
        pendingEntries.add(SplitGetterEntry(data, isBytesMode, getterName))
        return getterName
    }

    /**
     * 请求生成key字节数组的getter，返回其方法名（幂等，多次请求只生成一次）
     */
    fun requestKeyGetter(key: String): String {
        if (keyBytes == null) {
            keyBytes = key.toByteArray()
            keyGetterName = allocateName(KEY_GETTER_NAME)
        }
        return keyGetterName!!
    }

    /**
     * 是否有已注册但尚未发射的内容（拆分条目或key getter）
     */
    fun hasPending(): Boolean = pendingEntries.isNotEmpty() || keyBytes != null

    /**
     * 在ClassVisitor上发射所有辅助getter方法
     * 在ClassVisitor.visitEnd()中调用，直接发射给下游visitor（绕过加密变换）
     * @param ownerClassName 被转换类的内部名，用于生成方法间的invokestatic调用
     */
    fun emitGetters(cv: ClassVisitor, ownerClassName: String) {
        val key = keyBytes
        if (key != null) {
            emitBytesGetter(cv, ownerClassName, keyGetterName!!, key)
        }

        pendingEntries.forEachIndexed { index, entry ->
            if (entry.isBytesMode) {
                emitBytesEntry(cv, ownerClassName, index, entry)
            } else {
                emitStringEntry(cv, ownerClassName, index, entry)
            }
        }

        pendingEntries.clear()
        keyBytes = null
        keyGetterName = null
    }

    // ---- BYTES模式 ----

    /**
     * 发射byte[]条目的getter：
     * 数据不超过CHUNK_SIZE时$entry_N直接返回数组；否则切分为$chunk_N_M并由$entry_N聚合
     */
    private fun emitBytesEntry(
        cv: ClassVisitor,
        owner: String,
        index: Int,
        entry: SplitGetterEntry
    ) {
        val data = entry.data as ByteArray
        val entryName = entry.getterName
        if (data.size <= CHUNK_SIZE) {
            emitBytesGetter(cv, owner, entryName, data)
            return
        }

        val chunkCount = (data.size + CHUNK_SIZE - 1) / CHUNK_SIZE
        val chunkNames = ArrayList<String>(chunkCount)
        for (chunkIndex in 0 until chunkCount) {
            val start = chunkIndex * CHUNK_SIZE
            val end = minOf(start + CHUNK_SIZE, data.size)
            val chunkName = allocateName(chunkGetterName(index, chunkIndex))
            chunkNames.add(chunkName)
            emitBytesGetter(cv, owner, chunkName, data.copyOfRange(start, end))
        }

        // 聚合方法：new byte[total] + System.arraycopy 各 chunk + 返回（每次重建，无缓存）
        // 注意System.arraycopy是静态方法，参数顺序(src, srcPos, dest, destPos, length)
        val mv = cv.visitMethod(
            Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC,
            entryName,
            "()[B",
            null,
            null
        )
        mv.visitCode()
        pushInt(mv, data.size)
        mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_BYTE)
        mv.visitVarInsn(Opcodes.ASTORE, 0)
        for (chunkIndex in 0 until chunkCount) {
            val offset = chunkIndex * CHUNK_SIZE
            val length = minOf(CHUNK_SIZE, data.size - offset)
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                owner,
                chunkNames[chunkIndex],
                "()[B",
                false
            )
            mv.visitInsn(Opcodes.ICONST_0)                    // srcPos = 0
            mv.visitVarInsn(Opcodes.ALOAD, 0)                 // dest
            pushInt(mv, offset)                               // destPos
            pushInt(mv, length)                               // length
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/lang/System",
                "arraycopy",
                "(Ljava/lang/Object;ILjava/lang/Object;II)V",
                false
            )
        }
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(5, 1)
        mv.visitEnd()
    }

    /**
     * 发射直接返回指定byte[]的getter方法（用于$key、$chunk、小$entry）。
     * 无静态缓存，每次调用重新构建数组。
     */
    private fun emitBytesGetter(
        cv: ClassVisitor,
        owner: String,
        name: String,
        data: ByteArray
    ) {
        val mv = cv.visitMethod(
            Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC,
            name,
            "()[B",
            null,
            null
        )
        mv.visitCode()

        pushInt(mv, data.size)
        mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_BYTE)

        data.forEachIndexed { elementIndex, byte ->
            mv.visitInsn(Opcodes.DUP)
            pushInt(mv, elementIndex)
            pushInt(mv, byte.toInt())
            mv.visitInsn(Opcodes.BASTORE)
        }

        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(4, 0)
        mv.visitEnd()
    }

    // ---- STRING模式 ----

    /**
     * 发射String条目的getter：
     * 长度不超过CHUNK_SIZE时$entry_N直接LDC返回；否则切分为$chunk_N_M（各持一段LDC，
     * 规避常量池modified-UTF8单条目64KB上限），由$entry_N用StringBuilder拼接
     */
    private fun emitStringEntry(
        cv: ClassVisitor,
        owner: String,
        index: Int,
        entry: SplitGetterEntry
    ) {
        val data = entry.data as String
        val entryName = entry.getterName
        if (data.length <= CHUNK_SIZE) {
            emitStringGetter(cv, owner, entryName, data)
            return
        }

        val chunkCount = (data.length + CHUNK_SIZE - 1) / CHUNK_SIZE
        val chunkNames = ArrayList<String>(chunkCount)
        for (chunkIndex in 0 until chunkCount) {
            val start = chunkIndex * CHUNK_SIZE
            val end = minOf(start + CHUNK_SIZE, data.length)
            val chunkName = allocateName(chunkGetterName(index, chunkIndex))
            chunkNames.add(chunkName)
            emitStringGetter(cv, owner, chunkName, data.substring(start, end))
        }

        // 聚合方法：new StringBuilder 依次 append 各 chunk 后 toString + 返回（每次重建，无缓存）
        val mv = cv.visitMethod(
            Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC,
            entryName,
            "()Ljava/lang/String;",
            null,
            null
        )
        mv.visitCode()
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder")
        mv.visitInsn(Opcodes.DUP)
        mv.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            "java/lang/StringBuilder",
            "<init>",
            "()V",
            false
        )
        for (chunkIndex in 0 until chunkCount) {
            mv.visitInsn(Opcodes.DUP)
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                owner,
                chunkNames[chunkIndex],
                "()Ljava/lang/String;",
                false
            )
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                false
            )
            mv.visitInsn(Opcodes.POP)
        }
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/StringBuilder",
            "toString",
            "()Ljava/lang/String;",
            false
        )
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(3, 0)
        mv.visitEnd()
    }

    /**
     * 发射直接返回指定String常量的getter方法（用于小条目和$chunk）。
     * 无静态缓存，每次调用返回常量。
     */
    private fun emitStringGetter(
        cv: ClassVisitor,
        owner: String,
        name: String,
        data: String
    ) {
        val mv = cv.visitMethod(
            Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC,
            name,
            "()Ljava/lang/String;",
            null,
            null
        )
        mv.visitCode()
        mv.visitLdcInsn(data)
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(1, 0)
        mv.visitEnd()
    }

    // ---- 命名与指令工具 ----

    private fun entryGetterName(index: Int): String = "$ENTRY_PREFIX$index"

    private fun chunkGetterName(entryIndex: Int, chunkIndex: Int): String =
        "$CHUNK_PREFIX${entryIndex}_$chunkIndex"

    /**
     * 推送int值到栈上（优化指令选择）
     */
    private fun pushInt(mv: MethodVisitor, value: Int) {
        when (value) {
            in -1..5 -> mv.visitInsn(Opcodes.ICONST_0 + value)
            in Byte.MIN_VALUE..Byte.MAX_VALUE -> mv.visitIntInsn(Opcodes.BIPUSH, value)
            in Short.MIN_VALUE..Short.MAX_VALUE -> mv.visitIntInsn(Opcodes.SIPUSH, value)
            else -> mv.visitLdcInsn(value)
        }
    }

    companion object {
        const val ENTRY_PREFIX = "\$entry_"
        const val CHUNK_PREFIX = "\$chunk_"
        const val KEY_GETTER_NAME = "\$key"

        /**
         * 单个chunk承载的数据单元数（byte[]的字节数 / String的字符数）。
         * byte[] chunk最坏约8字节码/单元 → 4096×8 ≈ 32KB，对64KB方法上限留有充足余量；
         * String chunk为4096字符的LDC，常量池编码同样远低于64KB上限。
         */
        const val CHUNK_SIZE = 4096

        /**
         * byte[]内联到方法体的安全上限：超过则无条件走getter（即使方法尚未累计到拆分阈值），
         * 保证单条数据不会因自身内联字节码（最坏约8字节/单元）逼近64KB方法上限。
         */
        const val MAX_INLINE_BYTE_ARRAY = CHUNK_SIZE

        /**
         * String常量直接LDC内联的字符上限：常量池modified-UTF8单条目硬限制65535，
         * Base64密文为ASCII（1字节/字符），取60000留出余量；超过则走getter分块。
         */
        const val MAX_INLINE_UTF8_CHARS = 60000
    }
}

/**
 * 一条待发射的getter条目：加密数据 + 返回类型模式 + 分配好的getter方法名
 */
private data class SplitGetterEntry(
    val data: Any,          // String 或 ByteArray
    val isBytesMode: Boolean,
    val getterName: String
)
