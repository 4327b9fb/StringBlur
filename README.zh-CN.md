# StringBlur

[English](README.md) | [简体中文](README.zh-CN.md)

[![Maven Central](https://img.shields.io/maven-central/v/io.github.dawnuu/stringblur?style=flat-square)](https://central.sonatype.com/artifact/io.github.dawnuu/stringblur)

StringBlur 是一个 Android Gradle 插件，用于在构建阶段对 class 中的字符串常量进行加密，并在运行时自动解密。

## 安装

### plugins DSL / Version Catalog

> **当前版本 1.0.1：** 插件 marker 已发布到已授权的 `io.github.dawnuu` namespace 下。旧版插件 ID `stringblur` 仍通过下方的 `buildscript` 方式保留。

在 `settings.gradle(.kts)` 中配置插件仓库：

```groovy
// settings.gradle
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
```

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
```

在 `gradle/libs.versions.toml` 中声明插件：

```toml
[versions]
stringblur = "1.0.1"

[plugins]
stringblur = { id = "io.github.dawnuu.stringblur", version.ref = "stringblur" }
```

根目录 `build.gradle(.kts)`：

```groovy
// build.gradle
plugins {
    alias(libs.plugins.stringblur) apply false
}
```

```kotlin
// build.gradle.kts
plugins {
    alias(libs.plugins.stringblur) apply false
}
```

app 或 library 模块 `build.gradle(.kts)`：

```groovy
// build.gradle
plugins {
    alias(libs.plugins.stringblur)
}
```

```kotlin
// build.gradle.kts
plugins {
    alias(libs.plugins.stringblur)
}
```

不使用 Version Catalog 时，可以直接写：

```kotlin
plugins {
    id("io.github.dawnuu.stringblur") version "1.0.1"
}
```

### buildscript（旧版 ID）

也可以使用传统 `buildscript` 方式：

```groovy
// build.gradle
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath 'io.github.dawnuu:stringblur:1.0.1'
    }
}
```

```kotlin
// build.gradle.kts
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("io.github.dawnuu:stringblur:1.0.1")
    }
}
```

模块 `build.gradle(.kts)`：

```groovy
// build.gradle
apply plugin: 'stringblur'
```

```kotlin
// build.gradle.kts
apply(plugin = "stringblur")
```

## 迁移到 Maven Central

> **迁移变动：** 本次迁移是将自定义 GitHub Maven 仓库替换为 Maven Central，属于仓库和发布坐标迁移；旧版 `stringblur` ID 继续用于 `buildscript`，Plugins DSL 使用已授权的带 namespace ID。
>
> **1.0.0 提示：** 已发布的 `1.0.0` 不可修改，请使用修复依赖坐标并发布了 namespaced plugin marker 的 `1.0.1`。

| 项目 | 迁移前 | 迁移后 |
| --- | --- | --- |
| 仓库 | 旧版自定义 GitHub Maven 仓库（已移除） | `mavenCentral()` |
| 插件坐标 | `com.android.string.plugin:stringblur:2.1.0` | `io.github.dawnuu:stringblur:1.0.1` |
| 插件 ID | `stringblur` | `io.github.dawnuu.stringblur`（Plugins DSL）；`stringblur`（buildscript） |
| 源码包 | `com.android.string.plugin` | `com.android.string.plugin` |

### 旧版 `2.1.0` 的 Plugins DSL 接入方式

仅作历史参考，旧版 Kotlin DSL 的插件声明方式如下：

```kotlin
// 模块 build.gradle.kts
plugins {
    id("stringblur") version "2.1.0"
}
```

> 上面的 `id("stringblur")` 仅适用于旧版 `2.1.0`；当前 `1.0.1` 的 Plugins DSL 请使用 `id("io.github.dawnuu.stringblur")`，或继续使用上面的 `buildscript` 方式。

### Maven Central 坐标

发布后，两个构件使用以下 Maven Central 坐标：

- Gradle 插件：`io.github.dawnuu:stringblur:1.0.1`
- 公共 API：`io.github.dawnuu:common:1.0.1`

Java/Kotlin 源码包仍然保留为 `com.android.string.plugin`，只有 Maven 发布坐标使用 `io.github.dawnuu`。

### 自定义 Deployment 名称

已发布的 deployment 不能改名。后续版本可以使用自定义上传脚本，将 Central 中的 deployment 命名为 `StringBlur-<VERSION>`：

```bash
./scripts/publish-central.sh
```

可设置 `CENTRAL_DEPLOYMENT_NAME` 覆盖名称，使用 `--dry-run` 只构建 bundle 不上传，或使用 `--automatic` 请求验证通过后自动发布。

## 许可证

本项目采用 Apache License 2.0，详见 [LICENSE](LICENSE)。

## 配置

### Groovy DSL (build.gradle)

```groovy
import com.android.string.plugin.mode.BytesMode
import com.android.string.plugin.mode.Mode
import com.android.string.plugin.mode.SelectionStrategy

stringblur {
    key = "Hello World"
    enable = true

    whiteList = ['com.xxx.xxx.BuildConfig']
    encodePackages = ['com.xxx.xxx']

    modes = [Mode.XOR, Mode.SHIFT, Mode.XOR_SHIFT]
    bytesMode = BytesMode.STRING
    minLength = 3
    enableWhenDebug = false

    // 智能算法选择
    selectionStrategy = SelectionStrategy.SMART
    performanceWeight = 0.6
    securityWeight = 0.4
}
```

### Kotlin DSL (build.gradle.kts)

```kotlin
import com.android.string.plugin.mode.BytesMode
import com.android.string.plugin.mode.Mode
import com.android.string.plugin.mode.SelectionStrategy

stringblur {
    key = "Hello World"
    enable = true

    whiteList = listOf("com.xxx.xxx.BuildConfig")
    encodePackages = listOf("com.xxx.xxx")

    modes = listOf(Mode.XOR, Mode.SHIFT, Mode.XOR_SHIFT)
    bytesMode = BytesMode.STRING
    minLength = 3
    enableWhenDebug = false
    
    // 智能算法选择
    selectionStrategy = SelectionStrategy.SMART
    performanceWeight = 0.7  // 性能权重
    securityWeight = 0.3     // 安全权重
}
```

### 现代Kotlin DSL完整配置示例

```kotlin
// build.gradle.kts
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.stringblur)
}

android {
    // ... android配置
}

stringblur {
    enable = true
    key = "my-project-key-2024"
    minLength = 3
    enableWhenDebug = false
    
    // 加密算法配置
    modes = listOf(
        Mode.XOR_SIMD,    // SIMD优化的批量XOR
        Mode.FAST_ROT,    // 快速位旋转算法
        Mode.REVERSE      // 字节反转
    )
    bytesMode = BytesMode.RANDOM
    
    // 智能算法选择配置
    selectionStrategy = SelectionStrategy.SMART
    performanceWeight = 0.7  // 70%性能权重
    securityWeight = 0.3     // 30%安全权重
    
    // 白名单配置
    whiteList = listOf(
        "BuildConfig",
        "R",
        "R2",
        "com.android.string.plugin"
    )
}
```

## 配置项对照表

### Groovy DSL vs Kotlin DSL

| 配置项 | Groovy DSL | Kotlin DSL |
|-------|-----------|-----------|
| 加密密钥 | `key = "string"` | `key = "string"` |
| 启用插件 | `enable = true` | `enable = true` |
| 加密算法 | `modes = [Mode.XOR]` | `modes = listOf(Mode.XOR)` |
| Bytes模式 | `bytesMode = BytesMode.STRING` | `bytesMode = BytesMode.STRING` |
| 最小长度 | `minLength = 3` | `minLength = 3` |
| Debug模式 | `enableWhenDebug = false` | `enableWhenDebug = false` |
| 选择策略 | `selectionStrategy = SelectionStrategy.SMART` | `selectionStrategy = SelectionStrategy.SMART` |
| 性能权重 | `performanceWeight = 0.6` | `performanceWeight = 0.6` |
| 安全权重 | `securityWeight = 0.4` | `securityWeight = 0.4` |
| 白名单 | `whiteList = ["xxx"]` | `whiteList = listOf("xxx")` |

### 详细说明

## AGP 兼容性

| AGP 版本 | 最低 Gradle 版本 | 最低 JDK 版本 | 状态 |
|----------|-----------------|--------------|------|
| 8.x | 8.x | 17 | ✅ 完全支持 |
| 7.x | 7.x | 11 | ✅ 完全支持 |
| 6.x | 6.7+ | 11 | ✅ 完全支持 |
| 5.x | 5.6.4+ | 8 | ⚠️ 部分支持 |

> **注意**：推荐使用 AGP 7.x 及以上版本以获得最佳体验。

### 兼容性说明

- **AGP 8.x**：支持所有新特性
- **AGP 7.x**：完全支持所有功能
- **AGP 6.x**：完全支持所有功能
- **AGP 5.x**：基础功能可用，部分高级特性可能不可用

### 版本建议

| 项目类型 | 推荐 AGP 版本 | 推荐 Gradle 版本 |
|---------|--------------|----------------|
| 新项目 | 8.x | 8.x |
| 维护中项目 | 7.x | 7.x |
| 旧项目 | 6.x | 6.7+ |

- `key`：加密密钥。支持字符串，也支持整数随机长度，例如 `key 16`。为避免明文密钥提交进版本库，`key` 也可以不写在构建脚本中，插件会按以下顺序回退读取：Gradle property `stringblur.key`（`gradle.properties` 或 `-P` 传入）→ 环境变量 `STRINGBLUR_KEY` → 项目根目录 `local.properties` 中的 `stringblur.key`；全部缺失时构建报错。
- `enable`：是否开启字符串加密，默认 `false`。
- `whiteList`：类名或包名前缀白名单，匹配到的 class 不处理。
- `encodePackages`：加密范围。`null` 表示处理全部 class；空列表表示只处理当前 applicationId/namespace；非空列表会在当前 applicationId/namespace 基础上追加包名前缀。
- `modes`：加密方式列表。每个字符串会从列表中随机选择一种方式，默认 `[Mode.DEFAULT]`。
- `bytesMode`：密文承载方式，默认 `BytesMode.STRING`。
- `minLength`：最小加密长度，长度小于该值的字符串会跳过，默认 `0`。
- `enableWhenDebug`：debug构建时是否启用加密，默认 `false`。设置为 `true` 时debug构建也会执行加密。
- `selectionStrategy`：算法选择策略，默认 `SelectionStrategy.RANDOM` 保持原有随机行为。可设置为 `SMART` 启用智能选择。
- `performanceWeight`：性能权重 (0.0-1.0)，仅在 `SelectionStrategy.SMART` 时生效，默认 `0.5`。
- `securityWeight`：安全权重 (0.0-1.0)，仅在 `SelectionStrategy.SMART` 时生效，默认 `0.5`。

## 加密方式

- `Mode.DEFAULT`：按 key 对字节做加减变换。
- `Mode.XOR`：按 key 对字节做异或变换。
- `Mode.REVERSE`：反转字节顺序。
- `Mode.SHIFT`：按 key 低位对字节做位移变换。
- `Mode.XOR_SHIFT`：组合 XOR 与 SHIFT 变换。
- `Mode.XOR_SIMD`：SIMD优化的批量XOR加密，性能提升3-5倍，推荐用于性能敏感场景。
- `Mode.FAST_ROT`：基于位旋转的快速加密算法，适合高频小字符串加密场景。

## 密文承载方式

- `BytesMode.STRING`：密文写成字符串常量。
- `BytesMode.BYTES`：密文写成 byte array。
- `BytesMode.RANDOM`：每个字符串随机选择 `STRING` 或 `BYTES`。

## 加密报告

开启插件后会按 variant 生成报告文件：

```text
build/reports/stringblur/{variant}.txt
```

报告内容包括：
- 📊 **执行统计**：扫描类数量、加密字符串数量、跳过数量
- ⏱️ **性能分析**：执行时间、加密速率、性能评级
- 📈 **算法分布**：各加密算法使用占比和数量
- 📏 **长度分布**：按字符串长度分类的统计
- 🎯 **优化建议**：基于统计数据的性能建议

### 📄 **报告示例**

```text
StringBlur Performance Report
Generated: 2026-06-19 14:30:25
Variant: release
Config: XOR_SIMD,FAST_ROT,REVERSE (STRING mode)
========================================

Events:
SCAN class=com/example/MainActivity
ENCRYPT class=com/example/MainActivity method=initString mode=XOR_SIMD bytesMode=STRING length=12
SCAN class=com/example/ApiService  
ENCRYPT class=com/example/ApiService method=getToken mode=FAST_ROT bytesMode=STRING length=24

========================================
PERFORMANCE SUMMARY
========================================
Execution Time: 1,245ms

Overall Statistics:
  Classes Scanned: 156
  Classes Skipped: 12
  Strings Encrypted: 892
  Strings Ignored: 342

Algorithm Distribution:
  XOR_SIMD: 312 strings (35%)
  FAST_ROT: 223 strings (25%)
  REVERSE: 178 strings (20%)
  XOR_SHIFT: 134 strings (15%)
  XOR: 45 strings (5%)

String Length Distribution:
  1-8 chars: 156 strings
  9-20 chars: 234 strings
  21-50 chars: 298 strings
  51-100 chars: 156 strings
  101-200 chars: 48 strings

Performance Assessment:
  ⭐⭐⭐⭐⭐ Very Fast (<1s)
  Encryption Rate: 716 strings/sec
```

## 智能算法选择（可选功能）

StringBlur 支持智能算法选择策略，可以根据字符串特征自动选择最佳加密算法：

### 选择策略

- `SelectionStrategy.RANDOM`：**默认**，完全随机选择（保持现有行为）
- `SelectionStrategy.SMART`：智能选择，基于字符串长度、内容敏感度和特征
- `SelectionStrategy.PERFORMANCE`：性能优先，选择最快的算法
- `SelectionStrategy.SECURITY`：安全优先，选择最安全的算法

### 智能选择示例

```groovy
stringblur {
    key 'Hello World'
    enable true
    
    // 基础配置：保持现有随机行为
    modes = [Mode.XOR, Mode.SHIFT, Mode.XOR_SHIFT]
    
    // 启用智能选择（可选）
    selectionStrategy = SelectionStrategy.SMART
    performanceWeight = 0.7  // 偏重性能
    securityWeight = 0.3     // 兼顾安全
}
```

### 智能选择规则

| 字符串特征 | 选择算法 | 说明 |
|------------|----------|------|
| 短字符串 (1-8字符) | FAST_ROT | 位旋转最快 |
| 中等长度 (9-50字符) | XOR_SIMD | SIMD批量处理 |
| 长字符串 (>200字符) | REVERSE | 内存操作最快 |
| 包含敏感词 | XOR_SHIFT | 安全性最高 |
| 数字为主 | FAST_ROT | 适合数字特征 |
| 二进制数据 | XOR_SIMD | 高效处理 |

### 使用建议

1. **保持现状**：不设置 `selectionStrategy` 即可保持现有随机行为
2. **性能优化**：设置 `selectionStrategy = SelectionStrategy.PERFORMANCE`
3. **安全优先**：设置 `selectionStrategy = SelectionStrategy.SECURITY`
4. **平衡选择**：设置 `selectionStrategy = SelectionStrategy.SMART` 并调整权重

报告事件包括：

- `SCAN`：扫描到 class。
- `SKIP`：class 因白名单或 `encodePackages` 范围被跳过。
- `ENCRYPT`：字符串已加密，并记录实际使用的 `mode` 和 `bytesMode`。
- `IGNORE`：LDC 常量未加密，例如空字符串、非字符串常量或长度小于 `minLength`。

## 注意事项

- 注解参数字符串不能替换为运行时解密调用，因此不会按普通字符串加密。
- 资源、Manifest、assets、raw 等文件不属于 class ASM 处理范围。
- 插件默认使用 `InstrumentationScope.ALL`，会处理项目 class 和依赖 class；依赖较多时构建耗时会增加。
- 运行时解密入口的类名与方法名由 `key`、variant、算法配置派生（不再固定为 `StringBlur`/`decrypt`）：配置不变则名字不变，不影响增量构建；不同项目或不同 key 的入口互不相同，针对固定入口的通用 hook 脚本会失效。

## 推荐项目

- [AabResGuard](https://github.com/dawnuu/AabResGuard) — Android AAB 资源混淆工具，保护 APK/AAB 中的资源文件不被轻易提取和逆向。
