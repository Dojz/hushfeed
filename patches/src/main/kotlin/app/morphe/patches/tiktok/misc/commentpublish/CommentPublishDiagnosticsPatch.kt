/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.commentpublish

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findMutableMethodOf
import app.morphe.util.getReference
import app.morphe.util.numberOfParameterRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PUBLISH_VIEW_MODEL_SUFFIX = "/commentlist/viewmodel/CommentPublishViewModel;"
private const val COMMENT = "Lcom/ss/android/ugc/aweme/comment/model/Comment;"
private const val FUNCTION1 = "Lkotlin/jvm/functions/Function1;"
private const val EXTENSION = "Lapp/morphe/extension/tiktok/comment/CommentPublishDiagnostics;"

/** The two event names the publish entry logs, on 46.2.3, 46.7.3 and 46.8.3 alike. */
private val ENTRY_STRINGS = setOf("click_comment_send", "invalid_post_comment")

/**
 * The publish entry: an instance method of the publish view model taking the publish
 * parameters, the comment being replied to and a completion callback, whose body logs the
 * send click. Its name is R8's and changes with every build; its shape and its strings do not.
 */
internal fun Method.isPublishEntry(): Boolean {
    if (returnType != "V" || parameterTypes.size != 3) return false
    if (parameterTypes[1].toString() != COMMENT || parameterTypes[2].toString() != FUNCTION1) return false
    val strings = implementation?.instructions?.mapNotNull {
        it.getReference<StringReference>()?.string
    }?.toSet() ?: return false
    return strings.containsAll(ENTRY_STRINGS)
}

/**
 * A call from the entry into the request builder of its own class: the method that takes the
 * comment replied to, the publish parameters and a flag. Everything before it is a check that
 * can return without a word; from it on, the comment is on its way to the server.
 */
internal fun Instruction.isHandOff(owner: String): Boolean {
    if (opcode != Opcode.INVOKE_VIRTUAL && opcode != Opcode.INVOKE_VIRTUAL_RANGE) return false
    val target = getReference<MethodReference>() ?: return false
    val parameters = target.parameterTypes.map(CharSequence::toString)
    return target.definingClass == owner && parameters.size == 3 &&
        parameters[0] == COMMENT && parameters[2] == "Z"
}

@Suppress("unused")
val commentPublishDiagnosticsPatch = bytecodePatch(
    name = "Comment publish diagnostics",
    description = "Says in the diagnostic report whether a comment send reached TikTok's " +
        "publish code, what it had in hand, and whether it returned early or handed the " +
        "comment to the request. A comment that never posts leaves no other trace.",
    default = true,
) {
    dependsOn(sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok4623())

    execute {
        val entries = mutableListOf<Pair<ClassDef, Method>>()
        classDefForEach { classDef ->
            if (!classDef.type.endsWith(PUBLISH_VIEW_MODEL_SUFFIX)) return@classDefForEach
            classDef.methods.filter { it.isPublishEntry() }.forEach { entries += classDef to it }
        }
        check(entries.size == 1) {
            "Comment publish diagnostics: expected one publish entry, found ${entries.size}" +
                entries.joinToString(prefix = " [", postfix = "]") { "${it.first.type}->${it.second.name}" }
        }
        val (classDef, entry) = entries.single()
        val method = mutableClassDefBy(classDef.type).findMutableMethodOf(entry)
        val implementation = method.implementation!!
        val instructions = implementation.instructions.toList()
        val parameterBase = implementation.registerCount - method.numberOfParameterRegisters
        check(parameterBase >= 1) {
            "Comment publish diagnostics: the publish entry has no local register to stage an exit number in."
        }

        // Every way out before the request, and every hand-off to it. Indices are read off
        // the untouched body and written highest first, so none of them moves under another.
        val exits = instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
        val handOffs = instructions.withIndex().filter { it.value.isHandOff(classDef.type) }.map { it.index }
        check(exits.isNotEmpty()) { "Comment publish diagnostics: the publish entry never returns." }
        check(handOffs.isNotEmpty()) {
            "Comment publish diagnostics: the publish entry never hands the comment to the request."
        }

        val insertions = exits.map { index ->
            index to """
                const/16 v0, $index
                invoke-static { v0 }, $EXTENSION->onPublishExit(I)V
            """
        } + handOffs.map { index ->
            index to "invoke-static {}, $EXTENSION->onPublishHandedOff()V"
        } + listOf(
            0 to "invoke-static/range { v$parameterBase .. v${parameterBase + 1} }, " +
                "$EXTENSION->onPublishRequested(Ljava/lang/Object;Ljava/lang/Object;)V",
        )
        insertions.sortedByDescending { it.first }.forEach { (index, code) ->
            method.addInstructionsAtControlFlowLabel(index, code)
        }
    }
}
