package com.android.string.plugin.trasform

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * LONG_PRNG 模式的每类加密数据发射器（分片+聚合架构，无静态缓存）。
 *
 * 每个被转换的类持有一个独立实例（由 ClassVisitorController 创建），
 * 负责收集该类所有 LONG_PRNG 加密字符串的加密字节数据，
 * 并在类 visitEnd 时生成 getter 方法链：
 *
 *  - `$longChunk_0()` / `$longChunk_1()` / ...：每个 chunk ≤ [CHUNK_SIZE] 字节，
 *    直接内联 byte[] 构建，保证单方法字节码 ≤ ~33KB（对 64KB 上限留余量）；
 *  - `$longData()`：聚合方法，每次调用通过 System.arraycopy 重新拼接所有 chunk 并返回。
 *
 * 统一路径：无论数据大小（1 字节 ~ 65535 字节）都走「分片 + 聚合」，
 * 避免此前小数据 direct、大数据 chunked 两条路径的行为分裂（含帧与栈信息差异）。
 * 单分片时等价于直接内联数组构建，只是多一次 arraycopy 的开销。
 *
 * 帧计算：不手写 visitFrame。AGP 管道以 COMPUTE_FRAMES_FOR_INSTRUMENTED_CLASSES
 * 模式重算被插桩类的帧；单元测试中生成类时用 COMPUTE_FRAMES 模拟该行为。
 *
 * 命名冲突处理：方法名（$longData/$longChunk_N）预留原有方法名集合，
 * 冲突时自动加 $N 后缀消歧。
 *
 * @author chancey
 * @date 2026/9/28
 */
class LongPrngDataEmitter {

    /** 加密数据缓冲 */
    private val dataBuffer = mutableListOf<Byte>()

    /** getter 方法名（分配后固定） */
    private var getterName: String? = null

    /** 已占用的方法名集合（用于消歧） */
    private val takenNames = HashSet<String>()

    /**
     * 登记被转换类中已存在的方法名，保证 getter 不重名。
     */
    fun reserveExistingNames(names: Collection<String>) {
        takenNames.addAll(names)
    }

    /**
     * 追加加密字节数据，返回当前偏移（用于编码到 long 值中）。
     */
    fun appendData(encryptedBytes: ByteArray): Int {
        val offset = dataBuffer.size
        for (b in encryptedBytes) {
            dataBuffer.add(b)
        }
        return offset
    }

    /**
     * 获取当前数据偏移（下一个 appendData 将从这个位置开始写入）。
     */
    fun currentOffset(): Int = dataBuffer.size

    /**
     * 请求 getter 方法名。首次调用时分配，后续返回相同名称。
     * 如果无数据则返回 null。
     */
    fun requestGetterName(): String? {
        if (dataBuffer.isEmpty()) return null
        if (getterName != null) return getterName
        getterName = allocateName("\$longData")
        return getterName
    }

    /**
     * 是否有待发射的数据
     */
    fun hasPending(): Boolean = dataBuffer.isNotEmpty()

    /**
     * 在类 visitEnd 时为当前类发射 getter 方法链。
     * 直接发射给下游 visitor（cv），绕过本类的字符串加密变换。
     * 所有数据统一走「分片 + 聚合」路径（数据 ≤ CHUNK_SIZE 时为单分片），无静态缓存。
     */
    fun emitGetter(cv: ClassVisitor, className: String) {
        val name = getterName ?: return
        if (dataBuffer.isEmpty()) return

        val data = dataBuffer.toByteArray()
        emitChunkedGetter(cv, className, name, data)
    }

    /**
     * 分片 + 聚合 生成：
     *
     * 生成结构：
     * 1. N 个 `$longChunk_N()` 方法，每个内联 ≤ CHUNK_SIZE 字节
     * 2. `$longData()` 方法：new byte[total] + System.arraycopy 各 chunk → 返回（每次重建，无缓存）
     */
    private fun emitChunkedGetter(cv: ClassVisitor, className: String, getterName: String, data: ByteArray) {
        val chunkCount = (data.size + CHUNK_SIZE - 1) / CHUNK_SIZE
        val chunkNames = ArrayList<String>(chunkCount)

        // 1. 发射各 chunk getter
        for (chunkIndex in 0 until chunkCount) {
            val start = chunkIndex * CHUNK_SIZE
            val end = minOf(start + CHUNK_SIZE, data.size)
            val chunkName = allocateName("\$longChunk_${chunkIndex}")
            chunkNames.add(chunkName)
            emitDirectGetter(cv, chunkName, data.copyOfRange(start, end))
        }

        // 2. 发射 $longData() 聚合方法（每次构建，无缓存）
        val mv = cv.visitMethod(
            Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC,
            getterName,
            "()[B",
            null,
            null
        )
        mv.visitCode()

        // dst = new byte[total]
        pushInt(mv, data.size)
        mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_BYTE)
        mv.visitVarInsn(Opcodes.ASTORE, 0)

        // 依次 System.arraycopy 各 chunk
        for (chunkIndex in 0 until chunkCount) {
            val offset = chunkIndex * CHUNK_SIZE
            val length = minOf(CHUNK_SIZE, data.size - offset)
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                className,
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

        // return dst
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitInsn(Opcodes.ARETURN)

        mv.visitMaxs(6, 1)
        mv.visitEnd()
    }

    /**
     * 生成 byte[] 构建字节码（单分片 getter 方法体）
     */
    private fun emitDirectGetter(cv: ClassVisitor, name: String, data: ByteArray) {
        val mv = cv.visitMethod(
            Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC,
            name,
            "()[B",
            null,
            null
        )
        mv.visitCode()
        writeByteArray(mv, data)
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(4, 0)
        mv.visitEnd()
    }

    /**
     * 生成 byte[] 构建字节码
     */
    private fun writeByteArray(mv: MethodVisitor, data: ByteArray) {
        pushInt(mv, data.size)
        mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_BYTE)
        var i = 0
        while (i < data.size) {
            mv.visitInsn(Opcodes.DUP)
            pushInt(mv, i)
            pushInt(mv, data[i].toInt())
            mv.visitInsn(Type.BYTE_TYPE.getOpcode(Opcodes.IASTORE))
            i++
        }
    }

    /**
     * 生成 int 值压栈指令
     */
    private fun pushInt(mv: MethodVisitor, value: Int) {
        when (value) {
            in -1..5 -> mv.visitInsn(Opcodes.ICONST_0 + value)
            in Byte.MIN_VALUE..Byte.MAX_VALUE -> mv.visitIntInsn(Opcodes.BIPUSH, value)
            in Short.MIN_VALUE..Short.MAX_VALUE -> mv.visitIntInsn(Opcodes.SIPUSH, value)
            else -> mv.visitLdcInsn(value)
        }
    }

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

    companion object {
        /**
         * 单个 chunk 承载的最大字节数。
         * byte[] 内联最坏约 8 字节码/字节 → 4096×8 ≈ 32KB，对 64KB 方法上限留充足余量。
         */
        const val CHUNK_SIZE = 4096
    }
}
