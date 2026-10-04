package com.android.string.plugin.trasform

import com.android.string.plugin.field.StringFiled
import com.android.string.plugin.mode.BytesMode
import com.android.string.plugin.mode.Mode
import com.android.string.plugin.mode.SelectionStrategy
import com.android.string.plugin.report.StringBlurReport
import com.android.string.plugin.trasform.visitor.ClinitMethodVisitor
import com.android.string.plugin.trasform.visitor.InitMethodVisitor
import com.android.string.plugin.trasform.visitor.NormalMethodVisitor
import com.android.string.plugin.trasform.visitor.SensitiveApiDetector
import com.android.string.plugin.trasform.visitor.StringDeferringMethodVisitor
import com.android.string.plugin.util.AsmWriter
import com.android.string.plugin.util.ModeUtils
import com.android.string.plugin.util.SmartAlgorithmSelector
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import kotlin.random.Random

/**
 * @author chancey
 * @date   2023/9/6   22:54
 **/
class ClassVisitorController(
    private val wrapperClass: String,
    private val wrapperMethod: String,
    private val key: String,
    private val bytesMode: BytesMode,
    private val modes: List<Mode>,
    private val reportPath: String?,
    private val minLength: Int,
    val skipSensitiveApi: Boolean = true,
    private val selectionStrategy: SelectionStrategy = SelectionStrategy.RANDOM,
    private val performanceWeight: Double = 0.5,
    private val securityWeight: Double = 0.5,
    private val dataSplitThreshold: Int = 50
) {
    private val smartSelector = SmartAlgorithmSelector()
    private val asmWriter = AsmWriter(wrapperClass, wrapperMethod)
    var currentClassName: String? = null

    // 当前类的getter发射器（每个visitor/controller实例独立，不跨类共享状态）
    private val splitGetterEmitter = SplitGetterEmitter()

    fun isSensitiveCall(owner: String?, name: String?): Boolean {
        return skipSensitiveApi && SensitiveApiDetector.isSensitive(owner, name)
    }

    // 类级 @KeepString / @EncryptString（由 StringBlurClassVisitor.visitAnnotation 设置）
    var classKeep: Boolean = false
    var classEncrypt: Boolean = false

    val staticFinalFields = mutableListOf<StringFiled>()
    val staticFields = mutableListOf<StringFiled>()
    val finalFields = mutableListOf<StringFiled>()
    private val fields = mutableListOf<StringFiled>()
    private var isClInitExists = false
    private val random = Random(key.hashCode())

    // 方法大小跟踪（字节）
    private var currentMethodSize = 0
    private var splitMode = false

    /**
     * 累计内联密文的拆分阈值：当方法内注入的加密字节码累计超过此值时，
     * 后续密文改为getter调用，控制调用方方法体积。
     * 公开API不暴露此参数（始终用默认值50KB），仅保留为构造参数供测试注入小阈值。
     * 48KB上界：dex/JVM方法code长度硬限制65535字节(~64KB)，余量留给原方法自身字节码。
     */
    private val methodSizeThreshold: Int =
        dataSplitThreshold.coerceIn(1, MAX_METHOD_SIZE_THRESHOLD_KB) * 1024

    fun markFieldAnnotation(name: String, keep: Boolean, force: Boolean) {
        (staticFinalFields + staticFields + finalFields + fields)
            .filter { it.name == name }
            .forEach {
                if (keep) {
                    it.keep = true
                }
                if (force) {
                    it.force = true
                }
            }
    }

    fun isKeepStaticField(name: String?): Boolean {
        if (name == null) {
            return false
        }
        return staticFields.any { it.name == name && it.keep } ||
                staticFinalFields.any { it.name == name && it.keep }
    }

    fun isForceStaticField(name: String?): Boolean {
        if (name == null) {
            return false
        }
        return staticFields.any { it.name == name && it.force } ||
                staticFinalFields.any { it.name == name && it.force }
    }

    fun isKeepInstanceField(name: String?): Boolean {
        if (name == null) {
            return false
        }
        return finalFields.any { it.name == name && it.keep } ||
                fields.any { it.name == name && it.keep }
    }

    fun visitField(access: Int, name: String?, desc: String?, value: String?) {
        if (classKeep || name.isNullOrBlank() || desc != StringFiled.DESC) {
            return
        }
        val isStatic = (access and Opcodes.ACC_STATIC) != 0
        val isFinal = (access and Opcodes.ACC_FINAL) != 0
        val field = StringFiled(name, value)
        when {
            // static final, in this condition, the value is null or not null.
            isStatic && isFinal -> staticFinalFields += field
            // static, in this condition, the value is null.
            isStatic && !isFinal -> staticFields += field
            // final, in this condition, the value is null or not null.
            !isStatic && isFinal -> finalFields += field
            // normal, in this condition, the value is null.
            else -> fields += field
        }
    }

    fun visitEnd(mv: MethodVisitor) {
        mv.visitCode()
        // Here init static final fields.
        staticFinalFields.forEach {
            if (it.keep) {
                return@forEach
            }
            if (!it.force && !overflow(it.value)) {
                return@forEach
            }
            write(it.value, mv)
            mv.visitFieldInsn(Opcodes.PUTSTATIC, currentClassName, it.name, StringFiled.DESC)
        }
        mv.visitInsn(Opcodes.RETURN)
        // 该clinit由插件合成、直连下游visitor（不经过延迟visitor的visitMaxs兜底），
        // 必须自己声明注入代码的栈高水位：BYTES内联构造byte[]峰值为4
        mv.visitMaxs(StringDeferringMethodVisitor.INJECTED_CODE_MAX_STACK, 0)
        mv.visitEnd()
    }

    fun isVisitClInitMethod(): Boolean {
        return !isClInitExists && staticFinalFields.any { !it.keep }
    }

    fun visitMethod(
        access: Int,
        mv: MethodVisitor,
        name: String?,
        sensitiveLdcOrdinals: Set<Int> = emptySet(),
        maxLocals: Int = 0
    ): MethodVisitor {
        // 重置方法跟踪
        currentMethodSize = 0
        splitMode = false

        return when (name) {
            // If clinit exists meaning the static fields (not final) would have be inited here.
            "<clinit>" -> {
                isClInitExists = true
                ClinitMethodVisitor(mv, this, name, sensitiveLdcOrdinals, maxLocals)
            }
            // Here init final(not static) and normal fields
            "<init>" -> InitMethodVisitor(mv, this, name, sensitiveLdcOrdinals, maxLocals)
            else -> NormalMethodVisitor(access, mv, this, name, sensitiveLdcOrdinals, maxLocals)
        }
    }


    fun overflow(data: String?): Boolean {
        return data != null && ModeUtils.getEncodeImpl(modes.first())
            .overflow(data.toByteArray()) && data.length >= minLength
    }

    fun reportEncrypted(
        methodName: String?,
        data: String?,
        mode: Mode,
        selectedBytesMode: BytesMode
    ) {
        reportPath?.let {
            StringBlurReport.encrypted(
                it,
                currentClassName,
                methodName,
                data,
                mode.name,
                selectedBytesMode.name
            )
        }
    }

    fun reportIgnored(methodName: String?, value: Any?, reason: String) {
        reportPath?.let {
            StringBlurReport.ignored(
                it,
                currentClassName,
                methodName,
                value,
                reason
            )
        }
    }

    fun reportIgnoredLdc(methodName: String?, value: Any?) {
        if (value is String && !ModeUtils.getEncodeImpl(modes.first())
                .overflow(value.toByteArray())
        ) {
            reportIgnored(methodName, value, "emptyString")
        } else if (value is String && value.length < minLength) {
            reportIgnored(methodName, value, "tooShort")
        } else if (value !is String) {
            reportIgnored(methodName, value, "notStringLdc")
        }
    }

    fun write(data: String?, mv: MethodVisitor, methodName: String? = null) {
        val modeIndex = selectModeIndex(data ?: "")
        val mode = modes[modeIndex]
        val selectedBytesMode = selectBytesMode()
        val stringBlurWrapper = ModeUtils.getEncodeImpl(mode)
        reportEncrypted(methodName, data, mode, selectedBytesMode)

        val isBytesMode = selectedBytesMode == BytesMode.BYTES

        // 先加密以获取实际数据大小，避免重复加密
        val encryptedData: Any = if (isBytesMode) {
            stringBlurWrapper.encryptBytes(data, key)
        } else {
            stringBlurWrapper.encryptString(data, key)
        }

        // 基于加密后数据精确估算字节码大小
        // BYTES模式: 每个字节约6字节(DUP+index+value+BASTORE) + 数组创建 + key数组 + 调用
        // STRING模式: LDC引用常量池，字节码固定约15字节
        val estimatedSize = if (isBytesMode) {
            (encryptedData as ByteArray).size * 7 + 120
        } else {
            15
        }
        currentMethodSize += estimatedSize

        // 检查是否需要切换到split模式（方法累计内联字节码超阈值）
        if (!splitMode && currentMethodSize > methodSizeThreshold) {
            splitMode = true
        }

        // 单条数据自身过大时无条件走getter+分块（不依赖方法累计阈值）：
        //  - BYTES：内联byte[]最坏约8字节码/字节，单条超限会让所在方法直接撞64KB
        //  - STRING：常量池modified-UTF8单条目硬限制65535，巨型密文LDC会让ClassWriter直接报错
        val oversizedItem = if (isBytesMode) {
            (encryptedData as ByteArray).size > SplitGetterEmitter.MAX_INLINE_BYTE_ARRAY
        } else {
            (encryptedData as String).length > SplitGetterEmitter.MAX_INLINE_UTF8_CHARS
        }

        if (splitMode || oversizedItem) {
            // split模式：将加密数据存入辅助类，生成getter调用替代内联
            writeWithSplit(encryptedData, modeIndex, isBytesMode, mv)
        } else {
            // 正常模式：内联数据
            if (isBytesMode) {
                writeByBytes(encryptedData as ByteArray, modeIndex, mv)
            } else {
                writeByString(encryptedData as String, modeIndex, mv)
            }
        }
    }

    /**
     * STRING模式内联写入（已加密数据）
     */
    private fun writeByString(encryptedText: String, modeIndex: Int, mv: MethodVisitor) {
        asmWriter.write(encryptedText, key, modeIndex, mv)
    }

    /**
     * BYTES模式内联写入（已加密数据）
     * 密文数据内联在调用点；key数组全类唯一定义为$key() getter，各调用点统一引用，
     * 避免每个调用点重复构造key数组（约40字节/次的浪费）
     */
    private fun writeByBytes(encryptedData: ByteArray, modeIndex: Int, mv: MethodVisitor) {
        val className = currentClassName
            ?: throw IllegalStateException("currentClassName is null during writeByBytes — write() should only be called during method visitation")
        asmWriter.write(encryptedData, mv)
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            className,
            splitGetterEmitter.requestKeyGetter(key),
            "()[B",
            false
        )
        asmWriter.write(modeIndex, mv)
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            wrapperClass,
            wrapperMethod,
            "([B[BI)Ljava/lang/String;",
            false
        )
    }

    /**
     * split模式写入：将加密数据提取为当前类的getter方法，以invokestatic调用替代内联
     */
    private fun writeWithSplit(
        encryptedData: Any,
        modeIndex: Int,
        isBytesMode: Boolean,
        mv: MethodVisitor
    ) {
        val className = currentClassName
            ?: throw IllegalStateException("currentClassName is null during writeWithSplit — write() should only be called during method visitation")

        // 注册超限数据，拿到其getter方法名（getter在类visitEnd时统一发射到当前类）
        val getterName = splitGetterEmitter.registerEntry(encryptedData, isBytesMode)

        if (isBytesMode) {
            // BYTES模式: invokestatic CurrentClass.$entry_N()[B
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                className,
                getterName,
                "()[B",
                false
            )
            // key通过全类唯一的$key() getter引用（与内联路径一致）
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                className,
                splitGetterEmitter.requestKeyGetter(key),
                "()[B",
                false
            )
            // modeIndex
            asmWriter.write(modeIndex, mv)
            // invokestatic decrypt([B[BI)Ljava/lang/String;
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                wrapperClass,
                wrapperMethod,
                "([B[BI)Ljava/lang/String;",
                false
            )
        } else {
            // STRING模式: invokestatic CurrentClass.$entry_N()Ljava/lang/String;
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                className,
                getterName,
                "()Ljava/lang/String;",
                false
            )
            // key
            mv.visitLdcInsn(key)
            // modeIndex
            asmWriter.write(modeIndex, mv)
            // invokestatic decrypt(Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/String;
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                wrapperClass,
                wrapperMethod,
                "(Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/String;",
                false
            )
        }
    }

    /**
     * 当前类是否有待发射的辅助getter（split条目或BYTES模式的$key getter）
     */
    fun hasSplitGetters(): Boolean = splitGetterEmitter.hasPending()

    /**
     * 登记被转换类原有方法名，保证合成的getter不与已有方法重名。
     * 必须在方法体回放（任何write调用）之前完成
     */
    fun reserveExistingMethodNames(names: Collection<String>) {
        splitGetterEmitter.reserveExistingNames(names)
    }

    /**
     * 在类visitEnd时为当前类发射辅助getter方法
     * getter方法直接添加到当前类中，确保被AGP管道处理并编入DEX
     */
    fun emitSplitGetters(cv: ClassVisitor) {
        currentClassName?.let { splitGetterEmitter.emitGetters(cv, it) }
    }

    private fun selectBytesMode(): BytesMode {
        return when (bytesMode) {
            BytesMode.STRING -> BytesMode.STRING
            BytesMode.BYTES -> BytesMode.BYTES
            BytesMode.RANDOM -> if (random.nextBoolean()) BytesMode.BYTES else BytesMode.STRING
        }
    }

    private fun selectModeIndex(content: String): Int {
        if (modes.size == 1) {
            return 0
        }

        // 使用智能选择器选择最佳算法
        val selectedMode = smartSelector.selectBestAlgorithm(
            content = content,
            modes = modes,
            strategy = selectionStrategy,
            performanceWeight = performanceWeight,
            securityWeight = securityWeight
        )

        return modes.indexOf(selectedMode).takeIf { it >= 0 } ?: random.nextInt(modes.size)
    }

    private companion object {
        /** 拆分阈值（KB）的上界：见 methodSizeThreshold 注释 */
        const val MAX_METHOD_SIZE_THRESHOLD_KB = 60
    }
}