package com.android.string.plugin.trasform.visitor

import com.android.string.plugin.trasform.ClassVisitorController
import org.objectweb.asm.MethodVisitor

/**
 * @author chancey
 * @date   2023/12/9   20:33
 **/
class InitMethodVisitor(
    mv: MethodVisitor,
    controller: ClassVisitorController,
    methodName: String?
) : StringDeferringMethodVisitor(mv, controller, methodName) {

    override fun flushPending(value: String, sensitive: Boolean) {
        // We don't care about whether the field is final or normal
        if (sensitive) {
            controller.reportIgnored(methodName, value, "sensitiveApi")
            writePlainLdc(value)
            return
        }
        controller.write(value, mv, methodName)
    }
}
