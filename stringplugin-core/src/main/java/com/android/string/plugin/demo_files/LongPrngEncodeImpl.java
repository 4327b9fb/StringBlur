package com.android.string.plugin.demo_files;
//package applicationId.stringblur;

import com.android.string.plugin.IString;

/**
 * Long+PRNG+查找表加密算法实现
 *
 * <p>核心原理：
 * <ul>
 *   <li>每个字符串被编码为单个 long 常量（8字节），反编译后只看到数字</li>
 *   <li>long 值编码了：PRNG种子、字符串长度、加密数据偏移</li>
 *   <li>加密字符存储在每类专属的 byte[] 静态字段中（由 LongPrngDataEmitter 生成）</li>
 *   <li>运行时通过 PRNG 密钥流 + 固定查找表 XOR 还原明文</li>
 * </ul>
 *
 * <p>long 布局（64位）：
 * <pre>
 *   [63:48] 加密数据偏移(16bit) | [47:32] 字符串长度(16bit) | [31:0] PRNG种子(32bit)
 * </pre>
 *
 * <p>定位说明：
 * 这是「防静态分析」的字符串混淆手段，不是加密强度保证——
 * long 常量中的种子、查找表与解密算法全部内置于 APK，攻击者拿到 APK 即可完整复现解密。
 * 其价值在于：反编译源码中看不到明文，只看到数字常量与字节数组，提高人工逆向成本。
 * 每串独立随机种子（种子编码在 long 内），相同明文在不同位置得到不同密文。
 *
 * @author chancey
 * @date 2026/9/28
 */
public final class LongPrngEncodeImpl implements IString {

    // ==================== long 布局常量 ====================
    public static final int SEED_BITS = 32;
    public static final int LENGTH_BITS = 16;
    public static final int OFFSET_BITS = 16;

    public static final long SEED_MASK = (1L << SEED_BITS) - 1;           // 32bit
    public static final long LENGTH_MASK = (1L << LENGTH_BITS) - 1;       // 16bit
    public static final long OFFSET_MASK = (1L << OFFSET_BITS) - 1;       // 16bit

    public static final int SEED_SHIFT = 0;
    public static final int LENGTH_SHIFT = SEED_BITS;                     // 32
    public static final int OFFSET_SHIFT = SEED_BITS + LENGTH_BITS;       // 48

    /** 最大字符串长度（16bit） */
    public static final int MAX_STRING_LENGTH = 65535;

    /** 最大加密数据偏移（16bit） */
    public static final int MAX_DATA_OFFSET = 65535;

    // ==================== 查找表常量 ====================
    /** 查找表每条 String 的长度 */
    public static final int TABLE_ENTRY_SIZE = 8191;

    /** 查找表条目数 */
    public static final int TABLE_ENTRY_COUNT = 16;

    // ==================== 查找表缓存（编译期同 key 复用） ====================

    /** 缓存的查找表种子 */
    private static long cachedTableSeed;
    /** 缓存的查找表 */
    private static String[] cachedTable;

    /**
     * 从加密密钥派生查找表种子（编译期和运行时必须使用相同 key）。
     * 同 key 同表：支持增量构建、多模块安全。
     * 不同 key 不同表：不同 APK 的查找表互不相同。
     *
     * @param key 加密密钥（通常是 applicationId）
     * @return 64bit 查找表种子
     */
    public static long deriveTableSeed(String key) {
        return murmurHash3Mix(key.hashCode());
    }

    /**
     * 获取指定种子的查找表（带缓存，编译期同 key 复用避免重复生成）。
     */
    static String[] getTableForSeed(long tableSeed) {
        if (cachedTable == null || cachedTableSeed != tableSeed) {
            cachedTable = generateTable(tableSeed);
            cachedTableSeed = tableSeed;
        }
        return cachedTable;
    }

    // ==================== PRNG 常量（SplitMix64 变体） ====================
    private static final long MIX_CONST1 = 0x9e3779b97f4a7c15L;
    private static final long MIX_CONST2 = 0xbf58476d1ce4e5b9L;
    private static final long MIX_CONST3 = 0x94d049bb133111ebL;

    // ==================== MurmurHash3 混合常量 ====================
    private static final long MURMUR_C1 = 0x62a9d9ed799705f5L;
    private static final long MURMUR_C2 = 0xcb24d0a5c88c35b3L;

    // ==================== IString 接口实现 ====================

    @Override
    public byte[] encrypt(byte[] data, String key) {
        // LONG_PRNG 模式不使用 byte[] 加密路径，降级时返回原数据
        return data;
    }

    @Override
    public byte[] decrypt(byte[] data, byte[] key) {
        // LONG_PRNG 模式不使用 byte[] 解密路径，降级时返回原数据
        return data;
    }

    /**
     * 完整的 LONG_PRNG 加密方法，返回 long 值和加密字节数组。
     * 由 ClassVisitorController 调用，dataOffset 由 LongPrngDataEmitter 管理。
     *
     * @param data       明文字符串
     * @param key        加密密钥（用于派生查找表种子；PRNG 种子随每串随机生成并编码进 long）
     * @param dataOffset 当前加密数据在类缓冲中的偏移
     * @return 加密结果（long 值 + 加密字节数组）
     */
    public static EncryptResult encryptWithData(String data, String key, int dataOffset) {
        int len = data.length();
        if (len == 0 || len > MAX_STRING_LENGTH) {
            throw new IllegalArgumentException(
                "LONG_PRNG mode requires string length 1-65535, got: " + len);
        }
        if (dataOffset + len * 2 > MAX_DATA_OFFSET) {
            throw new IllegalStateException(
                "Encrypted data offset overflow: " + (dataOffset + len * 2) + " > " + MAX_DATA_OFFSET);
        }

        // 从 key 派生查找表种子，确保编译期和运行时一致
        long keyHash = murmurHash3Mix(key.hashCode());
        long tableSeed = keyHash;  // deriveTableSeed(key) == murmurHash3Mix(key.hashCode())

        // 生成 PRNG 种子：基于 key 哈希 + 随机偏移，确保 32bit 范围内
        // 种子会被编码进 long 值，运行时从 long 中还原，因此无需跨构建确定性
        java.util.Random rng = new java.util.Random(keyHash ^ System.nanoTime());
        long seed = rng.nextLong() & SEED_MASK;
        if (seed == 0) seed = 1;  // 种子不能为0

        // 用 PRNG 生成密钥流并加密（每个 char 拆为高低2字节，支持完整 Unicode）
        long prngState = seed;
        byte[] encrypted = new byte[len * 2];
        String[] table = getTableForSeed(tableSeed);

        for (int i = 0; i < len; i++) {
            char c = data.charAt(i);
            int hi = (c >>> 8) & 0xFF;  // 高字节
            int lo = c & 0xFF;           // 低字节

            // 高字节加密
            prngState = splitMix64(prngState);
            int keyByteHi = (int)(prngState >>> 32) & 0xFF;
            int tableIdxHi = (int)((prngState >>> 16) & 0x7FFF) % TABLE_ENTRY_COUNT;
            int charOffsetHi = (int)(prngState & 0x7FFFFFFF) % TABLE_ENTRY_SIZE;
            char tableCharHi = table[tableIdxHi].charAt(charOffsetHi);
            int tableByteHi = tableCharHi & 0xFF;
            encrypted[i * 2] = (byte)(hi ^ keyByteHi ^ tableByteHi);

            // 低字节加密
            prngState = splitMix64(prngState);
            int keyByteLo = (int)(prngState >>> 32) & 0xFF;
            int tableIdxLo = (int)((prngState >>> 16) & 0x7FFF) % TABLE_ENTRY_COUNT;
            int charOffsetLo = (int)(prngState & 0x7FFFFFFF) % TABLE_ENTRY_SIZE;
            char tableCharLo = table[tableIdxLo].charAt(charOffsetLo);
            int tableByteLo = tableCharLo & 0xFF;
            encrypted[i * 2 + 1] = (byte)(lo ^ keyByteLo ^ tableByteLo);
        }

        // 编码为 long
        long longValue = (seed << SEED_SHIFT)
                       | ((long)len << LENGTH_SHIFT)
                       | ((long)dataOffset << OFFSET_SHIFT);

        return new EncryptResult(longValue, encrypted);
    }

    /**
     * LONG_PRNG 加密结果
     */
    public static class EncryptResult {
        public final long longValue;
        public final byte[] encryptedBytes;

        public EncryptResult(long longValue, byte[] encryptedBytes) {
            this.longValue = longValue;
            this.encryptedBytes = encryptedBytes;
        }
    }

    /**
     * 从 long 值和加密数据解密还原字符串
     *
     * @param value 加密后的 long 值（含种子/长度/偏移）
     * @param data  每类加密数据（由运行时类的 $longData() getter 提供）
     * @param key   加密密钥（用于派生查找表种子）
     * @return 解密后的明文字符串
     */
    public String decryptFromLong(long value, byte[] data, String key) {
        long seed = (value >>> SEED_SHIFT) & SEED_MASK;
        int len = (int)((value >>> LENGTH_SHIFT) & LENGTH_MASK);
        int offset = (int)((value >>> OFFSET_SHIFT) & OFFSET_MASK);

        if (len == 0 || len > MAX_STRING_LENGTH || data == null || offset + len * 2 > data.length) {
            // 静默返回空串而非抛异常：数据异常在编译期已由 reportIgnored/reportEncrypted 报告，
            // 运行时数据损坏属于极端情况（正常构建不会发生），返回空串避免应用崩溃。
            return "";
        }

        // 从 key 派生查找表种子
        long tableSeed = deriveTableSeed(key);
        String[] table = getTableForSeed(tableSeed);

        long prngState = seed;
        char[] result = new char[len];

        for (int i = 0; i < len; i++) {
            // 高字节解密
            prngState = splitMix64(prngState);
            int keyByteHi = (int)(prngState >>> 32) & 0xFF;
            int tableIdxHi = (int)((prngState >>> 16) & 0x7FFF) % TABLE_ENTRY_COUNT;
            int charOffsetHi = (int)(prngState & 0x7FFFFFFF) % TABLE_ENTRY_SIZE;
            char tableCharHi = table[tableIdxHi].charAt(charOffsetHi);
            int tableByteHi = tableCharHi & 0xFF;
            int hi = (data[offset + i * 2] & 0xFF) ^ keyByteHi ^ tableByteHi;

            // 低字节解密
            prngState = splitMix64(prngState);
            int keyByteLo = (int)(prngState >>> 32) & 0xFF;
            int tableIdxLo = (int)((prngState >>> 16) & 0x7FFF) % TABLE_ENTRY_COUNT;
            int charOffsetLo = (int)(prngState & 0x7FFFFFFF) % TABLE_ENTRY_SIZE;
            char tableCharLo = table[tableIdxLo].charAt(charOffsetLo);
            int tableByteLo = tableCharLo & 0xFF;
            int lo = (data[offset + i * 2 + 1] & 0xFF) ^ keyByteLo ^ tableByteLo;

            // 合并为完整 char
            result[i] = (char)((hi << 8) | lo);
        }

        return new String(result);
    }

    // ==================== 查找表生成 ====================

    /**
     * 生成查找表（固定种子，确保编译期和运行时生成相同的表）
     */
    static String[] generateTable(long seed) {
        java.util.Random rng = new java.util.Random(seed);
        String[] table = new String[TABLE_ENTRY_COUNT];
        for (int i = 0; i < TABLE_ENTRY_COUNT; i++) {
            char[] chars = new char[TABLE_ENTRY_SIZE];
            for (int j = 0; j < TABLE_ENTRY_SIZE; j++) {
                int r = rng.nextInt(100);
                if (r < 90) {
                    // ASCII 可打印字符 (32-126)
                    chars[j] = (char)(32 + rng.nextInt(95));
                } else if (r < 97) {
                    // 扩展 Latin (128-255)
                    chars[j] = (char)(128 + rng.nextInt(128));
                } else {
                    // 常见 CJK 字符
                    chars[j] = (char)(0x4E00 + rng.nextInt(0x2000));
                }
            }
            table[i] = new String(chars);
        }
        return table;
    }

    // ==================== PRNG（SplitMix64 变体） ====================

    /**
     * SplitMix64 状态混合函数，用于 PRNG 推进。
     * 每次调用推进状态并返回新的状态值。
     * 密钥字节取自返回值的高32位。
     */
    static long splitMix64(long state) {
        state += MIX_CONST1;
        long z = state;
        z = (z ^ (z >>> 30)) * MIX_CONST2;
        z = (z ^ (z >>> 27)) * MIX_CONST3;
        return z ^ (z >>> 31);
    }

    // ==================== MurmurHash3 混合函数 ====================

    /**
     * MurmurHash3 最终混合（fmix64），用于将 key 哈希扩展为 PRNG 种子
     */
    static long murmurHash3Mix(int input) {
        long x = input & 0xFFFFFFFFL;
        x ^= x >>> 33;
        x *= MURMUR_C1;
        x ^= x >>> 28;
        x *= MURMUR_C2;
        x >>>= 32;
        return x;
    }
}
