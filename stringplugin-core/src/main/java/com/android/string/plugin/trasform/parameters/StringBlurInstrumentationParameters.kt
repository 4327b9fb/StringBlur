package com.android.string.plugin.trasform.parameters

import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.string.plugin.StringBlurExtension
import com.android.string.plugin.data.Constant
import com.android.string.plugin.mode.BytesMode
import com.android.string.plugin.mode.Mode
import com.android.string.plugin.mode.SelectionStrategy
import com.android.string.plugin.util.ModeUtils
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input

/**
 * @author chancey
 * @date   2025/6/20
 **/
abstract class StringBlurInstrumentationParameters : InstrumentationParameters {
    @get:Input
    abstract val key: Property<String>

    @get:Input
    abstract val bytesMode: Property<BytesMode>

    @get:Input
    abstract val applicationId: Property<String>

    @get:Input
    abstract val encodePackages: ListProperty<String>

    @get:Input
    abstract val whiteList: ListProperty<String>

    @get:Input
    abstract val modes: ListProperty<Mode>

    @get:Input
    abstract val minLength: Property<Int>

    @get:Input
    abstract val variantName: Property<String>

    @get:Input
    abstract val reportPath: Property<String>

    /** 运行时解密入口类的内部名（斜线分隔），按配置派生 */
    @get:Input
    abstract val wrapperClass: Property<String>

    /** 运行时解密入口方法名，按配置派生 */
    @get:Input
    abstract val wrapperMethod: Property<String>

    @get:Input
    abstract val skipSensitiveApi: Property<Boolean>

    @get:Input
    abstract val selectionStrategy: Property<SelectionStrategy>

    @get:Input
    abstract val performanceWeight: Property<Double>

    @get:Input
    abstract val securityWeight: Property<Double>

    fun setParams(
        key: String,
        applicationId: Provider<String>,
        extension: StringBlurExtension,
        variantName: String,
        reportPath: Provider<String>,
        modes: List<Mode>,
        wrapperClass: Provider<String>,
        wrapperMethod: Provider<String>
    ) {
        // key 由插件侧一次生成后传入：LONG_PRNG 运行时实现类内嵌的 key
        // 必须与编译期加密 key 完全一致。RandomGenerator 每次 generate() 都会产生
        // 新随机值，若在参数与 task 中各自调用会得到不同 key，导致解密乱码。
        this.key.set(key)
        this.bytesMode.set(extension.bytesMode)
        this.applicationId.set(applicationId)
        this.modes.addAll(modes)
        this.minLength.set(extension.minLength.coerceAtLeast(0))
        this.variantName.set(variantName)
        this.reportPath.set(reportPath)
        this.wrapperClass.set(wrapperClass)
        this.wrapperMethod.set(wrapperMethod)
        this.skipSensitiveApi.set(extension.skipSensitiveApi)
        this.selectionStrategy.set(extension.selectionStrategy)
        this.performanceWeight.set(extension.performanceWeight)
        this.securityWeight.set(extension.securityWeight)

        this.whiteList.addAll(extension.whiteList)
        this.whiteList.add("BuildConfig")
        this.whiteList.add("R2")
        this.whiteList.add("R")
        this.whiteList.add("IString")
        this.whiteList.add(Constant.DEFAULT_IMPL_CLASS_NAME)
        this.whiteList.add(applicationId.map { Constant.PLUGIN_CLASS_PACKAGE.format(it) })
        modes.forEach { mode ->
            this.whiteList.add(applicationId.map { ModeUtils.getEncodeImplClassFilePath(mode, it) })
        }

        if (extension.encodePackages != null) {
            this.encodePackages.add(applicationId)
            this.encodePackages.addAll(extension.encodePackages!!)
        }
    }
}
