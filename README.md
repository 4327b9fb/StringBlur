# StringBlur

[English](README.md) | [简体中文](README.zh-CN.md)

Latest version: `2.1.0`

StringBlur is an Android Gradle plugin that encrypts string constants in class files during the build and decrypts them automatically at runtime.

## Installation

### Plugins DSL / Version Catalog

Configure the plugin repository in `settings.gradle(.kts)`:

```groovy
pluginManagement {
    repositories {
        maven { url "https://raw.githubusercontent.com/dawnuu/maven/refs/heads/main/gradle/" }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
```

Declare and apply the plugin with a version catalog:

```toml
# gradle/libs.versions.toml
[plugins]
stringblur = { id = "stringblur", version = "2.1.0" }
```

```kotlin
// Root build.gradle.kts
plugins {
    alias(libs.plugins.stringblur) apply false
}

// Application or library module build.gradle.kts
plugins {
    alias(libs.plugins.stringblur)
}
```

The equivalent Groovy DSL uses `alias(libs.plugins.stringblur)` in `build.gradle`.

### Legacy `buildscript`

```groovy
buildscript {
    repositories {
        maven { url "https://raw.githubusercontent.com/dawnuu/maven/refs/heads/main/gradle/" }
        google()
        mavenCentral()
    }
    dependencies {
        classpath 'com.android.string.plugin:stringblur:2.1.0'
    }
}

// Module build.gradle
apply plugin: 'stringblur'
```

For Kotlin DSL, use `classpath("com.android.string.plugin:stringblur:2.1.0")` and `apply(plugin = "stringblur")`.

## Configuration

```kotlin
import com.android.string.plugin.mode.BytesMode
import com.android.string.plugin.mode.Mode
import com.android.string.plugin.mode.SelectionStrategy

stringblur {
    key = "my-project-key-2024"
    enable = true
    minLength = 3
    enableWhenDebug = false

    // Limit processing to these packages. Omit for all classes.
    encodePackages = listOf("com.example")
    whiteList = listOf("BuildConfig", "R", "R2")

    modes = listOf(Mode.XOR_SIMD, Mode.FAST_ROT, Mode.REVERSE)
    bytesMode = BytesMode.RANDOM

    selectionStrategy = SelectionStrategy.SMART
    performanceWeight = 0.7
    securityWeight = 0.3

    incremental = true
    cacheDir = file("build/string-blur-cache")
}
```

Groovy DSL accepts the same properties. Use lists such as `modes = [Mode.XOR, Mode.SHIFT]` and `whiteList = ['BuildConfig']`.

| Option | Description | Default |
| --- | --- | --- |
| `key` | Encryption key; accepts a string or a random integer length, for example `key 16`. | — |
| `enable` | Enables string encryption. | `false` |
| `whiteList` | Class-name or package-prefix exclusions. | — |
| `encodePackages` | Processing scope. `null` processes all classes; an empty list processes only the current `applicationId`/`namespace`; non-empty lists add package prefixes. | — |
| `modes` | Encryption modes. A mode is selected per string. | `[Mode.DEFAULT]` |
| `bytesMode` | Encrypted-data representation. | `BytesMode.STRING` |
| `minLength` | Strings shorter than this are skipped. | `0` |
| `enableWhenDebug` | Also encrypt debug builds. | `false` |
| `selectionStrategy` | Mode-selection strategy. | `SelectionStrategy.RANDOM` |
| `performanceWeight` / `securityWeight` | SMART-strategy weights from 0.0 to 1.0. | `0.5` / `0.5` |
| `incremental` | Process only changed files and strings when possible. | `true` |
| `cacheDir` | Incremental-build cache directory. | `build/string-blur-cache` |

## Encryption modes

- `Mode.DEFAULT`: key-based byte addition/subtraction.
- `Mode.XOR`: key-based XOR.
- `Mode.REVERSE`: reverses byte order.
- `Mode.SHIFT`: key-based byte shifting.
- `Mode.XOR_SHIFT`: combines XOR and SHIFT.
- `Mode.XOR_SIMD`: SIMD-optimized batch XOR, recommended for performance-sensitive code.
- `Mode.FAST_ROT`: fast bit-rotation algorithm for frequent short strings.

Encrypted data can be stored as a string (`BytesMode.STRING`), a byte array (`BytesMode.BYTES`), or randomly as either representation (`BytesMode.RANDOM`).

## Smart mode selection

`SelectionStrategy.RANDOM` preserves the original random behavior. The optional `SMART`, `PERFORMANCE`, and `SECURITY` strategies select modes based on string characteristics or the desired priority.

| String characteristic | Preferred mode |
| --- | --- |
| Short strings (1–8 characters) | `FAST_ROT` |
| Medium strings (9–50 characters) | `XOR_SIMD` |
| Long strings (over 200 characters) | `REVERSE` |
| Sensitive content | `XOR_SHIFT` |
| Mostly numeric or binary data | `FAST_ROT` or `XOR_SIMD` |

## Encryption report

Each variant produces a report at:

```text
build/reports/stringblur/{variant}.txt
```

The report includes scan and encryption counts, duration and throughput, algorithm and string-length distributions, plus optimization suggestions. Its events are `SCAN`, `SKIP`, `ENCRYPT`, and `IGNORE`.

## AGP compatibility

| AGP | Minimum Gradle | Minimum JDK | Status |
| --- | --- | --- | --- |
| 8.x | 8.x | 17 | Fully supported |
| 7.x | 7.x | 11 | Fully supported |
| 6.x | 6.7+ | 11 | Fully supported |
| 5.x | 5.6.4+ | 8 | Partial support |

AGP 7.x or newer is recommended. For new projects, use AGP and Gradle 8.x; for maintained projects, 7.x; for legacy projects, AGP 6.x with Gradle 6.7+.

## Notes

- Annotation string parameters cannot be replaced with runtime decryption calls, so they are not encrypted as ordinary strings.
- Resources, manifests, assets, and raw files are outside the class ASM processing scope.
- The plugin uses `InstrumentationScope.ALL` by default, so dependency classes are also processed and large dependency graphs can increase build time.

## Related project

- [AabResGuard](https://github.com/dawnuu/AabResGuard) — Android AAB resource obfuscation tool.
