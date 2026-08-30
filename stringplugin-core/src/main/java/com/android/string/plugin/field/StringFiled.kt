package com.android.string.plugin.field

/**
 * @author chancey
 * @date   2023/9/5   22:31
 **/
class StringFiled(val name: String, var value: String?) {
    // @KeepString 字段：值保持明文
    var keep: Boolean = false
    // @EncryptString 字段：强制参与加密（压过 minLength）
    var force: Boolean = false

    companion object {
        const val DESC = "Ljava/lang/String;"
    }
}
