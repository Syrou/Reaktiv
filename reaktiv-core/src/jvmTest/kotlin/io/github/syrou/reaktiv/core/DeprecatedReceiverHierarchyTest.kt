package io.github.syrou.reaktiv.core

import kotlin.test.Test
import kotlin.test.assertEquals

class DeprecatedReceiverHierarchyTest {

    @Deprecated("Control marker.", ReplaceWith("ModuleLogic"))
    private interface DeprecatedControl

    @Suppress("DEPRECATION")
    private class ControlLogic : ModuleLogic(), DeprecatedControl

    private fun Class<*>.hierarchy(): Set<Class<*>> =
        setOf(this) + (listOfNotNull(superclass) + interfaces).flatMap { it.hierarchy() }

    private fun replaceWithCarriers(vararg roots: Class<*>): List<String> =
        roots.flatMap { it.hierarchy() }
            .distinct()
            .filter { it.getAnnotation(Deprecated::class.java)?.replaceWith?.expression?.isNotEmpty() == true }
            .map { it.name }

    @Test
    fun detectsDeprecatedReplaceWithOnASupertype() {
        @Suppress("DEPRECATION")
        val expected = listOf(DeprecatedControl::class.java.name)
        assertEquals(expected, replaceWithCarriers(ControlLogic::class.java))
    }

    @Test
    fun moduleLogicAndStoreHierarchiesCarryNoDeprecatedReplaceWith() {
        assertEquals(emptyList(), replaceWithCarriers(ModuleLogic::class.java, Store::class.java))
    }
}
