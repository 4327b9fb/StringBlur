package com.android.string.plugin.trasform.visitor

import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/** Rewrites JDK string-concat invokedynamic calls so fixed text can be encrypted. */
object StringConcatRewriter {

    fun rewrite(
        visitor: MethodVisitor,
        name: String?,
        descriptor: String?,
        bootstrapMethodHandle: Handle?,
        bootstrapMethodArguments: Array<out Any?>,
        allocateLocals: (List<Type>) -> IntArray
    ): Boolean {
        if (descriptor == null ||
            bootstrapMethodHandle?.owner != CONCAT_FACTORY_OWNER ||
            name !in CONCAT_NAMES
        ) {
            return false
        }
        val recipe = if (name == "makeConcatWithConstants") {
            bootstrapMethodArguments.firstOrNull() as? String ?: return false
        } else {
            null
        }
        val constants = bootstrapMethodArguments.drop(1)
        if (name == "makeConcat" ||
            recipe == null ||
            recipe.all { it == ARGUMENT_MARKER || it == CONSTANT_MARKER } &&
            constants.none { it is String }
        ) {
            return false
        }
        if (constants.any { it !is String && it !is Int && it !is Long && it !is Float && it !is Double }) {
            return false
        }

        val argumentTypes = Type.getArgumentTypes(descriptor)
        val localIndices = allocateLocals(argumentTypes.toList())
        for (index in argumentTypes.indices.reversed()) {
            visitor.visitVarInsn(
                argumentTypes[index].getOpcode(Opcodes.ISTORE),
                localIndices[index]
            )
        }

        visitor.visitTypeInsn(Opcodes.NEW, STRING_BUILDER_OWNER)
        visitor.visitInsn(Opcodes.DUP)
        visitor.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            STRING_BUILDER_OWNER,
            "<init>",
            "()V",
            false
        )

        emitRecipe(visitor, recipe, argumentTypes.toList(), localIndices, constants)

        visitor.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            STRING_BUILDER_OWNER,
            "toString",
            "()Ljava/lang/String;",
            false
        )
        return true
    }

    private fun emitRecipe(
        visitor: MethodVisitor,
        recipe: String,
        argumentTypes: List<Type>,
        localIndices: IntArray,
        constants: List<Any?>
    ) {
        var argumentIndex = 0
        var constantIndex = 0
        var literalStart = 0
        recipe.forEachIndexed { index, character ->
            if (character != ARGUMENT_MARKER && character != CONSTANT_MARKER) {
                return@forEachIndexed
            }
            emitLiteral(visitor, recipe.substring(literalStart, index))
            if (character == ARGUMENT_MARKER && argumentIndex < argumentTypes.size) {
                emitArgument(visitor, argumentTypes[argumentIndex], localIndices[argumentIndex])
                argumentIndex++
            } else if (character == CONSTANT_MARKER && constantIndex < constants.size) {
                emitConstant(visitor, constants[constantIndex++])
            }
            literalStart = index + 1
        }
        emitLiteral(visitor, recipe.substring(literalStart))
    }

    private fun emitLiteral(visitor: MethodVisitor, value: String) {
        if (value.isNotEmpty()) {
            visitor.visitLdcInsn(value)
            visitor.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                STRING_BUILDER_OWNER,
                "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                false
            )
        }
    }

    private fun emitArgument(visitor: MethodVisitor, type: Type, localIndex: Int) {
        visitor.visitVarInsn(type.getOpcode(Opcodes.ILOAD), localIndex)
        emitAppend(visitor, type)
    }

    private fun emitConstant(visitor: MethodVisitor, value: Any?) {
        visitor.visitLdcInsn(value)
        val type = when (value) {
            is String -> Type.getType(String::class.java)
            is Long -> Type.LONG_TYPE
            is Float -> Type.FLOAT_TYPE
            is Double -> Type.DOUBLE_TYPE
            else -> Type.INT_TYPE
        }
        emitAppend(visitor, type)
    }

    private fun emitAppend(visitor: MethodVisitor, type: Type) {
        val appendDescriptor = when (type.sort) {
            Type.BOOLEAN -> "(Z)Ljava/lang/StringBuilder;"
            Type.CHAR -> "(C)Ljava/lang/StringBuilder;"
            Type.BYTE, Type.SHORT, Type.INT -> "(I)Ljava/lang/StringBuilder;"
            Type.LONG -> "(J)Ljava/lang/StringBuilder;"
            Type.FLOAT -> "(F)Ljava/lang/StringBuilder;"
            Type.DOUBLE -> "(D)Ljava/lang/StringBuilder;"
            Type.OBJECT -> if (type.descriptor == "Ljava/lang/String;") {
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;"
            } else {
                "(Ljava/lang/Object;)Ljava/lang/StringBuilder;"
            }

            else -> "(Ljava/lang/Object;)Ljava/lang/StringBuilder;"
        }
        visitor.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            STRING_BUILDER_OWNER,
            "append",
            appendDescriptor,
            false
        )
    }

    private const val CONCAT_FACTORY_OWNER = "java/lang/invoke/StringConcatFactory"
    private const val STRING_BUILDER_OWNER = "java/lang/StringBuilder"
    private const val ARGUMENT_MARKER = '\u0001'
    private const val CONSTANT_MARKER = '\u0002'
    private val CONCAT_NAMES = setOf("makeConcat", "makeConcatWithConstants")
}
