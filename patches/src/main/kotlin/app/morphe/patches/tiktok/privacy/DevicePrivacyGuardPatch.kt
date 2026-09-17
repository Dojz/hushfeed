/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.privacy

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

private const val EXTENSION = "Lapp/morphe/extension/tiktok/privacy/DevicePrivacyGuard;"
private data class ClipboardSite(val owner: ClassDef, val method: Method, val index: Int, val managerReg: Int)

@Suppress("unused")
val devicePrivacyGuardPatch = bytecodePatch(
    name = "Device privacy guard",
    description = "Blocks TikTok from reading your clipboard contents and scanning devices on your local network. Clipboard writes (copying links you asked for) are not affected.",
    default = false,
) {
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok4623())

    execute {
        val clipboardTargets = setOf(
            "Landroid/content/ClipboardManager;->getPrimaryClip()Landroid/content/ClipData;",
            "Landroid/content/ClipboardManager;->getText()Ljava/lang/CharSequence;",
            "Landroid/content/ClipboardManager;->hasPrimaryClip()Z",
        )
        val sites = mutableListOf<ClipboardSite>()
        classDefForEach { owner ->
            if (owner.type.startsWith("Lapp/morphe/extension/")) return@classDefForEach
            owner.methods.forEach { method ->
                method.implementation?.instructions?.forEachIndexed { index, instruction ->
                    val ref = instruction.getReference<MethodReference>()?.toString() ?: return@forEachIndexed
                    if (ref !in clipboardTargets) return@forEachIndexed
                    if (instruction.opcode != Opcode.INVOKE_VIRTUAL) return@forEachIndexed
                    val invoke = instruction as FiveRegisterInstruction
                    sites += ClipboardSite(owner, method, index, invoke.registerC)
                }
            }
        }
        sites.forEach { site ->
            mutableClassDefBy(site.owner).findMutableMethodOf(site.method).replaceInstruction(
                site.index,
                "invoke-static { v${site.managerReg} }, $EXTENSION->interceptClipboardRead(Landroid/content/ClipboardManager;)Landroid/content/ClipData;",
            )
        }
        println("[Device privacy guard] Intercepted ${sites.size} clipboard read sites.")
    }
}
