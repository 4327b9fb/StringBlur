package com.android.string.plugin.task.build

import com.android.string.plugin.data.Constant
import com.android.string.plugin.demo_files.LongPrngEncodeImpl
import com.android.string.plugin.mode.Mode
import com.android.string.plugin.task.BaseFile
import com.palantir.javapoet.ArrayTypeName
import com.palantir.javapoet.ClassName
import com.palantir.javapoet.FieldSpec
import com.palantir.javapoet.JavaFile
import com.palantir.javapoet.MethodSpec
import com.palantir.javapoet.TypeName
import com.palantir.javapoet.TypeSpec
import java.io.File
import javax.lang.model.element.Modifier

/**
 * 生成 java 代码：LONG_PRNG 模式运行时实现类。
 * 内容对应 [com.android.string.plugin.demo_files.LongPrngEncodeImpl]。
 *
 * key 处理：**不在运行时类内嵌 key 字符串**。查找表种子在编译期由 key 派生
 * （murmurHash3Mix(key.hashCode())），运行时类只内嵌派生后的 64 位 TABLE_SEED 常量——
 * 反编译只能看到数字，看不到 key 明文。
 *
 * 调用链（运行时）：
 *   被加密方法 --invokestatic--> Wrapper.{wrapperMethod}Long(J[B)Ljava/lang/String;
 *   Wrapper --IMPL_N.decryptLong(value,data)--> 本类 decryptLong
 *   本类 --decryptFromLong(value,data)--> 还原明文（查找表由内嵌 TABLE_SEED 生成）
 *
 * 编译期（ClassVisitorController）与运行时使用同一算法与同一种子：
 * 编译期 encryptWithData 用 deriveTableSeed(key) 建表，运行时用内嵌 TABLE_SEED 建表，
 * 二者数值相等（同由 murmurHash3Mix(key.hashCode()) 派生），保证解密一致。
 *
 * @author chancey
 * @date 2026/9/28
 */
class LongPrngEncodeImplFile : BaseFile() {

    override fun getImplClassName() = Constant.LONG_PRNG_IMPL_CLASS_NAME

    /**
     * LONG_PRNG 必须携带 key 生成（编译期派生查找表种子后内嵌 TABLE_SEED 常量）。
     * 无 key 的默认路径会生成不可用的占位实现，仅供类名推导等场景使用。
     */
    override fun create(baseDir: File, applicationId: String, modes: List<Mode>, key: String) {
        val typeSpec = buildTypeSpec(applicationId, modes, key)
        val pkg = Constant.PLUGIN_CLASS_PACKAGE.format(applicationId)
        JavaFile.builder(pkg, typeSpec).build().writeTo(baseDir)
    }

    override fun buildTypeSpec(applicationId: String, modes: List<Mode>): TypeSpec {
        return buildTypeSpec(applicationId, modes, "")
    }

    private fun buildTypeSpec(applicationId: String, modes: List<Mode>, key: String): TypeSpec {
        val byteArrayType = ArrayTypeName.of(TypeName.BYTE)
        val stringArrayType = ArrayTypeName.of(String::class.java)

        // 编译期由 key 派生查找表种子：运行时只内嵌种子值，key 字符串不进 APK
        val tableSeed = LongPrngEncodeImpl.deriveTableSeed(key)

        return TypeSpec.classBuilder(getImplClassName())
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addSuperinterface(ClassName.bestGuess(Constant.ABSTRACT_CLASS_NAME))
            // ==================== 查找表种子（编译期由 key 派生，反编译只见数字） ====================
            .addField(
                FieldSpec.builder(TypeName.LONG, "TABLE_SEED", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .initializer("\$L", "${tableSeed}L")
                    .build()
            )
            // ==================== long 布局常量 ====================
            .addField(constField("SEED_BITS", TypeName.INT, 32))
            .addField(constField("LENGTH_BITS", TypeName.INT, 16))
            .addField(constField("OFFSET_BITS", TypeName.INT, 16))
            .addField(constField("SEED_MASK", TypeName.LONG, "(1L << SEED_BITS) - 1"))
            .addField(constField("LENGTH_MASK", TypeName.LONG, "(1L << LENGTH_BITS) - 1"))
            .addField(constField("OFFSET_MASK", TypeName.LONG, "(1L << OFFSET_BITS) - 1"))
            .addField(constField("SEED_SHIFT", TypeName.INT, 0))
            .addField(constField("LENGTH_SHIFT", TypeName.INT, "SEED_BITS"))
            .addField(constField("OFFSET_SHIFT", TypeName.INT, "SEED_BITS + LENGTH_BITS"))
            .addField(constField("MAX_STRING_LENGTH", TypeName.INT, 65535))
            .addField(constField("MAX_DATA_OFFSET", TypeName.INT, 65535))
            // ==================== 查找表常量 ====================
            .addField(constField("TABLE_ENTRY_SIZE", TypeName.INT, 8191))
            .addField(constField("TABLE_ENTRY_COUNT", TypeName.INT, 16))
            // ==================== 查找表缓存 ====================
            .addField(
                FieldSpec.builder(TypeName.LONG, "cachedTableSeed", Modifier.PRIVATE, Modifier.STATIC).build()
            )
            .addField(
                FieldSpec.builder(stringArrayType, "cachedTable", Modifier.PRIVATE, Modifier.STATIC).build()
            )
            // ==================== PRNG 常量 ====================
            .addField(constField("MIX_CONST1", TypeName.LONG, "0x9e3779b97f4a7c15L"))
            .addField(constField("MIX_CONST2", TypeName.LONG, "0xbf58476d1ce4e5b9L"))
            .addField(constField("MIX_CONST3", TypeName.LONG, "0x94d049bb133111ebL"))
            // ==================== IString 实现（LONG_PRNG 不使用 byte[] 路径） ====================
            .addMethod(
                MethodSpec.methodBuilder("encrypt")
                    .addAnnotation(Override::class.java)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(byteArrayType)
                    .addParameter(byteArrayType, "data")
                    .addParameter(String::class.java, "key")
                    .addStatement("return data")
                    .build()
            )
            .addMethod(
                MethodSpec.methodBuilder("decrypt")
                    .addAnnotation(Override::class.java)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(byteArrayType)
                    .addParameter(byteArrayType, "data")
                    .addParameter(byteArrayType, "key")
                    .addStatement("return data")
                    .build()
            )
            // ==================== wrapper 入口：decryptLong(J[B) ====================
            .addMethod(
                MethodSpec.methodBuilder("decryptLong")
                    .addModifiers(Modifier.PUBLIC)
                    .returns(String::class.java)
                    .addParameter(TypeName.LONG, "value")
                    .addParameter(byteArrayType, "data")
                    .addStatement("return decryptFromLong(value, data)")
                    .build()
            )
            // ==================== 核心解密 ====================
            .addMethod(buildDecryptFromLongMethod(byteArrayType))
            // ==================== 查找表 ====================
            .addMethod(
                MethodSpec.methodBuilder("getTableForSeed")
                    .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                    .returns(stringArrayType)
                    .addParameter(TypeName.LONG, "tableSeed")
                    .beginControlFlow("if (cachedTable == null || cachedTableSeed != tableSeed)")
                    .addStatement("cachedTable = generateTable(tableSeed)")
                    .addStatement("cachedTableSeed = tableSeed")
                    .endControlFlow()
                    .addStatement("return cachedTable")
                    .build()
            )
            .addMethod(
                MethodSpec.methodBuilder("generateTable")
                    .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                    .returns(stringArrayType)
                    .addParameter(TypeName.LONG, "seed")
                    .addStatement("java.util.Random rng = new java.util.Random(seed)")
                    .addStatement("String[] table = new String[TABLE_ENTRY_COUNT]")
                    .beginControlFlow("for (int i = 0; i < TABLE_ENTRY_COUNT; i++)")
                    .addStatement("char[] chars = new char[TABLE_ENTRY_SIZE]")
                    .beginControlFlow("for (int j = 0; j < TABLE_ENTRY_SIZE; j++)")
                    .addStatement("int r = rng.nextInt(100)")
                    .beginControlFlow("if (r < 90)")
                    .addStatement("chars[j] = (char)(32 + rng.nextInt(95))")
                    .nextControlFlow("else if (r < 97)")
                    .addStatement("chars[j] = (char)(128 + rng.nextInt(128))")
                    .nextControlFlow("else")
                    .addStatement("chars[j] = (char)(0x4E00 + rng.nextInt(0x2000))")
                    .endControlFlow()
                    .endControlFlow()
                    .addStatement("table[i] = new String(chars)")
                    .endControlFlow()
                    .addStatement("return table")
                    .build()
            )
            // ==================== PRNG ====================
            .addMethod(
                MethodSpec.methodBuilder("splitMix64")
                    .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                    .returns(TypeName.LONG)
                    .addParameter(TypeName.LONG, "state")
                    .addStatement("state += MIX_CONST1")
                    .addStatement("long z = state")
                    .addStatement("z = (z ^ (z >>> 30)) * MIX_CONST2")
                    .addStatement("z = (z ^ (z >>> 27)) * MIX_CONST3")
                    .addStatement("return z ^ (z >>> 31)")
                    .build()
            )
            .build()
    }

    private fun buildDecryptFromLongMethod(byteArrayType: ArrayTypeName): MethodSpec {
        return MethodSpec.methodBuilder("decryptFromLong")
            .addModifiers(Modifier.PUBLIC)
            .returns(String::class.java)
            .addParameter(TypeName.LONG, "value")
            .addParameter(byteArrayType, "data")
            .addStatement("long seed = (value >>> SEED_SHIFT) & SEED_MASK")
            .addStatement("int len = (int)((value >>> LENGTH_SHIFT) & LENGTH_MASK)")
            .addStatement("int offset = (int)((value >>> OFFSET_SHIFT) & OFFSET_MASK)")
            .beginControlFlow("if (len == 0 || len > MAX_STRING_LENGTH || data == null || offset + len * 2 > data.length)")
            // 静默返回空串而非抛异常：数据异常在编译期已由 reportIgnored/reportEncrypted 报告，
            // 运行时数据损坏属于极端情况（正常构建不会发生），返回空串避免应用崩溃。
            .addStatement("return \"\"")
            .endControlFlow()
            // 种子在编译期由 key 派生后内嵌，运行时不再需要 key
            .addStatement("String[] table = getTableForSeed(TABLE_SEED)")
            .addStatement("long prngState = seed")
            .addStatement("char[] result = new char[len]")
            .beginControlFlow("for (int i = 0; i < len; i++)")
            .addStatement("prngState = splitMix64(prngState)")
            .addStatement("int keyByteHi = (int)(prngState >>> 32) & 0xFF")
            .addStatement("int tableIdxHi = (int)((prngState >>> 16) & 0x7FFF) % TABLE_ENTRY_COUNT")
            .addStatement("int charOffsetHi = (int)(prngState & 0x7FFFFFFF) % TABLE_ENTRY_SIZE")
            .addStatement("char tableCharHi = table[tableIdxHi].charAt(charOffsetHi)")
            .addStatement("int tableByteHi = tableCharHi & 0xFF")
            .addStatement("int hi = (data[offset + i * 2] & 0xFF) ^ keyByteHi ^ tableByteHi")
            .addStatement("prngState = splitMix64(prngState)")
            .addStatement("int keyByteLo = (int)(prngState >>> 32) & 0xFF")
            .addStatement("int tableIdxLo = (int)((prngState >>> 16) & 0x7FFF) % TABLE_ENTRY_COUNT")
            .addStatement("int charOffsetLo = (int)(prngState & 0x7FFFFFFF) % TABLE_ENTRY_SIZE")
            .addStatement("char tableCharLo = table[tableIdxLo].charAt(charOffsetLo)")
            .addStatement("int tableByteLo = tableCharLo & 0xFF")
            .addStatement("int lo = (data[offset + i * 2 + 1] & 0xFF) ^ keyByteLo ^ tableByteLo")
            .addStatement("result[i] = (char)((hi << 8) | lo)")
            .endControlFlow()
            .addStatement("return new String(result)")
            .build()
    }

    private fun constField(name: String, type: TypeName, value: Any): FieldSpec {
        return FieldSpec.builder(type, name, Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .initializer("\$L", value)
            .build()
    }
}
