package com.android.string.plugin.trasform.visitor

import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.AnalyzerException
import org.objectweb.asm.tree.analysis.SourceInterpreter
import org.objectweb.asm.tree.analysis.SourceValue
import java.util.IdentityHashMap

/**
 * Finds string LDC instructions whose value can reach a String argument of a
 * sensitive method, regardless of argument order or intervening stack ops.
 */
object SensitiveStringAnalyzer {

    fun findSensitiveLdcOrdinals(owner: String?, method: MethodNode): Set<Int> {
        if (owner == null) {
            return emptySet()
        }

        val instructions = method.instructions.toArray()
        val ldcOrdinals = IdentityHashMap<AbstractInsnNode, Int>()
        val instructionIndices = IdentityHashMap<AbstractInsnNode, Int>()
        var ordinal = 0
        instructions.forEachIndexed { index, instruction ->
            instructionIndices[instruction] = index
            if (instruction is LdcInsnNode && instruction.cst is String) {
                ldcOrdinals[instruction] = ordinal++
            }
        }
        if (ldcOrdinals.isEmpty()) {
            return emptySet()
        }

        val frames = try {
            Analyzer<SourceValue>(SourceInterpreter()).analyze(owner, method)
        } catch (_: AnalyzerException) {
            // Keep the existing direct-adjacency fallback when bytecode cannot
            // be analyzed instead of failing the whole transform.
            return emptySet()
        }

        val result = linkedSetOf<Int>()
        instructions.forEachIndexed { index, instruction ->
            val call = instruction as? MethodInsnNode ?: return@forEachIndexed
            if (!SensitiveApiDetector.isSensitive(call.owner, call.name)) {
                return@forEachIndexed
            }

            val frame = frames[index] ?: return@forEachIndexed
            val arguments = Type.getArgumentTypes(call.desc)
            var stackIndex = frame.stackSize - arguments.sumOf { it.size }

            arguments.forEach { argument ->
                if (argument.descriptor == STRING_DESCRIPTOR &&
                    stackIndex >= 0 &&
                    stackIndex < frame.stackSize
                ) {
                    val sourceValue = frame.getStack(stackIndex)
                    collectLdcSources(
                        sourceValue,
                        frames,
                        instructionIndices,
                        ldcOrdinals,
                        result
                    )
                }
                stackIndex += argument.size
            }
        }
        return result
    }

    private fun collectLdcSources(
        value: SourceValue,
        frames: Array<org.objectweb.asm.tree.analysis.Frame<SourceValue>?>,
        instructionIndices: IdentityHashMap<AbstractInsnNode, Int>,
        ldcOrdinals: IdentityHashMap<AbstractInsnNode, Int>,
        result: MutableSet<Int>,
        visited: MutableSet<AbstractInsnNode> = java.util.Collections.newSetFromMap(IdentityHashMap())
    ) {
        value.insns.forEach { source ->
            if (!visited.add(source)) {
                return@forEach
            }
            when (source) {
                is LdcInsnNode -> if (source.cst is String) {
                    ldcOrdinals[source]?.let(result::add)
                }
                is VarInsnNode -> {
                    val sourceIndex = instructionIndices[source] ?: return@forEach
                    val sourceFrame = frames[sourceIndex] ?: return@forEach
                    when (source.opcode) {
                        org.objectweb.asm.Opcodes.ALOAD -> {
                            if (source.`var` < sourceFrame.locals) {
                                collectLdcSources(
                                    sourceFrame.getLocal(source.`var`),
                                    frames,
                                    instructionIndices,
                                    ldcOrdinals,
                                    result,
                                    visited
                                )
                            }
                        }
                        org.objectweb.asm.Opcodes.ASTORE -> {
                            if (sourceFrame.stackSize > 0) {
                                collectLdcSources(
                                    sourceFrame.getStack(sourceFrame.stackSize - 1),
                                    frames,
                                    instructionIndices,
                                    ldcOrdinals,
                                    result,
                                    visited
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private const val STRING_DESCRIPTOR = "Ljava/lang/String;"
}
