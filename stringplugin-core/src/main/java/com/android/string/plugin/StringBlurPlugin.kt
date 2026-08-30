package com.android.string.plugin

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.string.plugin.data.Constant
import com.android.string.plugin.task.StringBlurTask
import com.android.string.plugin.trasform.StringBlurClassTransform
import com.android.string.plugin.util.EntryNames
import com.android.string.plugin.util.Logger
import com.android.string.plugin.util.ModeUtils
import com.android.string.plugin.util.generator.KeyGenerator
import com.android.string.plugin.util.generator.RandomGenerator
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File
import java.util.Properties

class StringBlurPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.extensions.create(Constant.PLUGIN_NAME, StringBlurExtension::class.java)
        val components =
            target.extensions.findByType(ApplicationAndroidComponentsExtension::class.java)
                ?: target.extensions.findByType(LibraryAndroidComponentsExtension::class.java)
                ?: throw GradleException(Logger.text("请在 Android 项目中使用此插件"))

        components.onVariants { variant ->
            val stringblur = target.extensions.getByType(StringBlurExtension::class.java)
            if (!stringblur.enable) {
                Logger.log("功能关闭")
                return@onVariants
            }
            
            val isDebugBuild = variant.buildType == "debug"
            if (isDebugBuild && !stringblur.enableWhenDebug) {
                Logger.log("Debug模式下加密已关闭")
                return@onVariants
            }
            val resolvedKey = resolveKey(target, stringblur)
            val generator = when (resolvedKey) {
                is String -> KeyGenerator(resolvedKey)
                is Int -> RandomGenerator(resolvedKey)
                else -> null
            } ?: throw GradleException(Logger.text("加密key不能为空，请通过 stringblur { key = ... }、gradle property stringblur.key、环境变量 STRINGBLUR_KEY 或 local.properties 配置"))

            // AGP 8.13 起 namespace 为 Provider<String>，且在 onVariants 阶段不可 .get()，
            // 全部入口名与包装类路径必须惰性求值，否则会得到 "property 'namespace'" 这类垃圾值
            val applicationIdProvider = variant.namespace
            val modes = ModeUtils.resolveModes(stringblur.modes)
            // 解密入口按配置派生：配置不变名字不变（保证增量构建），
            // 不同项目/key/variant 的入口互不相同，通用 hook 脚本无法命中
            val wrapperNamesProvider = applicationIdProvider.map { appId ->
                EntryNames.derive(
                    listOf(
                        resolvedKey,
                        variant.name,
                        appId,
                        modes.joinToString(",") { it.name },
                        stringblur.bytesMode.name
                    ).joinToString("|")
                )
            }
            val wrapperClassName = wrapperNamesProvider.map { it.first }
            val wrapperMethodName = wrapperNamesProvider.map { it.second }
            val wrapperClass = applicationIdProvider.zip(wrapperNamesProvider) { appId, names ->
                "${Constant.PLUGIN_CLASS_PACKAGE.format(appId).replace(".", "/")}/${names.first}"
            }
            val reportFile = target.layout.buildDirectory
                .file("reports/${Constant.PLUGIN_NAME}/${variant.name}.txt")
            val reportPathString = reportFile.map { it.asFile.absolutePath }
            val reportPathFile = reportFile.map { it.asFile }

            variant.instrumentation.transformClassesWith(
                StringBlurClassTransform::class.java,
                InstrumentationScope.ALL
            ) { params ->
                params.setParams(generator, applicationIdProvider, stringblur, variant.name, reportPathString, modes, wrapperClass, wrapperMethodName)
            }

            variant.instrumentation.setAsmFramesComputationMode(FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_CLASSES)

            StringBlurTask.execute(
                target,
                variant,
                applicationIdProvider,
                modes,
                reportPathFile,
                stringblur.bytesMode,
                wrapperClassName,
                wrapperMethodName
            )
        }

        appendImplementations(target)
    }

    private fun appendImplementations(project: Project) {
        project.dependencies.add(
            "implementation",
            "io.github.dawnuu:common:$GRADLE_VERSION"
        )
    }

    /**
     * 解析加密 key：优先使用扩展里显式配置的值；
     * 未配置时依次回退 gradle property `stringblur.key`、环境变量 `STRINGBLUR_KEY`、
     * 项目根目录 local.properties 的 `stringblur.key`，避免明文密钥提交进版本库。
     */
    private fun resolveKey(project: Project, extension: StringBlurExtension): Any? {
        extension.key?.let { key ->
            if (key is Int || (key is String && key.isNotBlank())) {
                return key
            }
        }
        project.findProperty("stringblur.key")?.toString()?.takeIf { it.isNotBlank() }?.let {
            Logger.log("已使用 gradle property stringblur.key 作为加密key")
            return it
        }
        System.getenv("STRINGBLUR_KEY")?.takeIf { it.isNotBlank() }?.let {
            Logger.log("已使用环境变量 STRINGBLUR_KEY 作为加密key")
            return it
        }
        val localPropertiesFile = File(project.rootDir, "local.properties")
        if (localPropertiesFile.isFile) {
            val props = Properties()
            localPropertiesFile.inputStream().use { props.load(it) }
            props.getProperty("stringblur.key")?.takeIf { it.isNotBlank() }?.let {
                Logger.log("已使用 local.properties 中的 stringblur.key 作为加密key")
                return it
            }
        }
        return null
    }
}
