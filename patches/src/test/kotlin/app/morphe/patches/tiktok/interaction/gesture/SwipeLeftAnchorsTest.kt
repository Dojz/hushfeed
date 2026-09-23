package app.morphe.patches.tiktok.interaction.gesture

import app.morphe.Fixtures
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Swipe-left controls rests on, held to TikTok 47.0.3.
 *
 * TikTok's main pager, the one a left swipe on the feed slides to the creator's profile, is a
 * three-class chain: a base that alone declares setPagingMainValve and a private getIsPageEnabled
 * that its onInterceptTouchEvent and onTouchEvent both ask before the pager moves; a class that
 * declares getOnInterceptTouchEventListeners and its own onInterceptTouchEvent but no
 * onTouchEvent; and the concrete pager, which the main activity's layout alone creates and which
 * overrides none of them. So an early false from getIsPageEnabled holds every paging path, and
 * hooks at the start of the middle class's intercept and the base's onTouchEvent between them see
 * every event of a gesture the pager handles, and only on this pager.
 */
class SwipeLeftAnchorsTest {
    @Test
    fun `47_0_3 has one main pager, gated in one place, created by the main layout alone`() {
        val apk = Fixtures.apks().single { it.name.contains("47.0.3") }
        val classes = HashMap<String, ClassDef>()
        val container = DexFileFactory.loadDexContainer(apk, Opcodes.getDefault())
        container.dexEntryNames.forEach { entry ->
            container.getEntry(entry)!!.dexFile.classes.forEach { classes.putIfAbsent(it.type, it) }
        }

        val valves = classes.values.filter { classDef -> classDef.methods.any { it.name == "setPagingMainValve" } }
        assertEquals("classes declaring setPagingMainValve", 1, valves.size)
        val base = valves.single()
        val gate = base.methods.single { it.name == "getIsPageEnabled" && it.parameterTypes.isEmpty() && it.returnType == "Z" }
        listOf("onInterceptTouchEvent", "onTouchEvent").forEach { name ->
            val method = base.methods.single { it.name == name && it.parameterTypes.map { p -> p.toString() } == listOf(MOTION_EVENT) }
            assertTrue(
                "$name asks getIsPageEnabled",
                method.implementation!!.instructions.any { instruction ->
                    ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.let {
                        it.definingClass == base.type && it.name == gate.name
                    } == true
                },
            )
        }

        val middles = classes.values.filter { classDef -> classDef.methods.any { it.name == "getOnInterceptTouchEventListeners" } }
        assertEquals("classes declaring getOnInterceptTouchEventListeners", 1, middles.size)
        val middle = middles.single()
        assertEquals("the intercept class extends the gated base", base.type, middle.superclass)
        assertTrue(
            "the intercept class has its own onInterceptTouchEvent",
            middle.methods.any { it.name == "onInterceptTouchEvent" && it.parameterTypes.map { p -> p.toString() } == listOf(MOTION_EVENT) },
        )
        assertTrue(
            "the intercept class leaves onTouchEvent to the base",
            middle.methods.none { it.name == "onTouchEvent" || it.name == "getIsPageEnabled" },
        )

        val subclasses = classes.values.filter { it.superclass == base.type || it.superclass == middle.type }
        assertEquals("the chain below the base: ${subclasses.map { it.type }}", 2, subclasses.size)
        val concrete = subclasses.single { it.superclass == middle.type }
        assertTrue(
            "the concrete pager overrides none of the hooks",
            concrete.methods.none { it.name in setOf("getIsPageEnabled", "onInterceptTouchEvent", "onTouchEvent") },
        )
        val creators = classes.values.filter { classDef ->
            classDef.methods.any { method ->
                method.implementation?.instructions?.any { instruction ->
                    instruction.opcode == Opcode.NEW_INSTANCE &&
                        ((instruction as ReferenceInstruction).reference as TypeReference).type == concrete.type
                } == true
            }
        }.map { it.type }.toSet()
        assertEquals(
            "who creates the concrete pager",
            setOf("Lcom/by/andInflater/homepage_common_activity_main;"),
            creators,
        )
    }

    private companion object {
        const val MOTION_EVENT = "Landroid/view/MotionEvent;"
    }
}
