/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.gesture

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.findMutableMethodOf
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION = "Lapp/morphe/extension/tiktok/interaction/SwipeDirectionOverride;"
private data class PagerSite(val owner: ClassDef, val method: Method, val index: Int, val replacement: String)

@Suppress("unused")
val swipeOverridePatch = bytecodePatch(
    name = "Swipe direction override",
    description = "Controls what a left swipe on the feed does. By default it opens the creator's profile. Change it to do nothing or open comments instead.",
    default = false,
) {
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok4623())

    execute {
        val targets = setOf(
            "Landroidx/viewpager/widget/ViewPager;->setCurrentItem(I)V",
            "Landroidx/viewpager/widget/ViewPager;->setCurrentItem(IZ)V",
        )
        val sites = mutableListOf<PagerSite>()
        classDefForEach { owner ->
            if (owner.type.startsWith("Lapp/morphe/extension/")) return@classDefForEach
            owner.methods.forEach { method ->
                method.implementation?.instructions?.forEachIndexed { index, instruction ->
                    val ref = instruction.getReference<MethodReference>()?.toString() ?: return@forEachIndexed
                    if (ref !in targets) return@forEachIndexed
                    if (instruction.opcode != Opcode.INVOKE_VIRTUAL) return@forEachIndexed
                    val invoke = instruction as FiveRegisterInstruction
                    val pagerReg = invoke.registerC
                    val pageReg = invoke.registerD
                    sites += PagerSite(
                        owner, method, index,
                        "invoke-static { v$pagerReg, v$pageReg }, " +
                            "$EXTENSION->interceptPageChange(Landroidx/viewpager/widget/ViewPager;I)V",
                    )
                }
            }
        }
        sites.forEach { site ->
            mutableClassDefBy(site.owner).findMutableMethodOf(site.method).replaceInstruction(site.index, site.replacement)
        }
        println("[Swipe override] Intercepted ${sites.size} ViewPager.setCurrentItem sites.")
    }
}
