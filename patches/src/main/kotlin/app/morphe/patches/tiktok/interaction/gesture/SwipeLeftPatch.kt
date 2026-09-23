/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.gesture

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.interaction.blockauthor.blockAuthorPatch
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.requireLocals

private const val EXTENSION = "Lapp/morphe/extension/tiktok/interaction/GestureActions;"

/**
 * TikTok's main pager, the one a left swipe on the feed slides to the creator's profile. Its
 * base class is the only one that declares setPagingMainValve, and its private getIsPageEnabled
 * is asked by both its onInterceptTouchEvent and its onTouchEvent before the pager moves
 * (SwipeLeftAnchorsTest). The pager's own class is created only by the main activity's layout.
 */
internal object MainPagerPageEnabledFingerprint : Fingerprint(
    name = "getIsPageEnabled",
    returnType = "Z",
    parameters = listOf(),
    custom = { _, classDef -> classDef.methods.any { it.name == "setPagingMainValve" } },
)

/**
 * The same pager's touch intercept, one class down: the class that declares
 * getOnInterceptTouchEventListeners. It is offered each event of a gesture until the pager takes
 * the gesture or a child (a photo carousel, say) claims it.
 */
internal object MainPagerInterceptFingerprint : Fingerprint(
    name = "onInterceptTouchEvent",
    returnType = "Z",
    parameters = listOf("Landroid/view/MotionEvent;"),
    custom = { _, classDef -> classDef.methods.any { it.name == "getOnInterceptTouchEventListeners" } },
)

/**
 * The base's onTouchEvent, which gets the rest of a gesture once the pager has taken it. With
 * paging held, the base's intercept still takes a clearly leftward swipe (TikTok's own path for a
 * held pager, which only records the scroll) and hands the rest of it here, so the intercept and
 * this together see every event of a swipe no child claimed, and nothing of one a child did.
 */
internal object MainPagerTouchFingerprint : Fingerprint(
    name = "onTouchEvent",
    returnType = "Z",
    parameters = listOf("Landroid/view/MotionEvent;"),
    custom = { _, classDef -> classDef.methods.any { it.name == "setPagingMainValve" } },
)

@Suppress("unused")
val swipeLeftPatch = bytecodePatch(
    name = "Swipe-left controls",
    description = "Lets a left swipe on a feed video do nothing or open its comments instead of " +
        "opening the creator's profile. Switch: Hushfeed settings > Feed screen.",
    default = false,
) {
    category("Interaction")
    compatibleWith(*AppCompatibilities.tiktok4703())
    // Double-tap controls registers the comment buttons that "Open comments" presses.
    dependsOn(settingsPatch, sharedExtensionPatch, blockAuthorPatch, doubleTapPatch)
    execute {
        MainPagerPageEnabledFingerprint.method.apply {
            requireLocals("Swipe left", 1)
            addInstructionsWithLabels(0, """
                invoke-static/range {p0 .. p0}, $EXTENSION->allowProfileSwipe(Ljava/lang/Object;)Z
                move-result v0
                if-nez v0, :original
                const/4 v0, 0x0
                return v0
            """, ExternalLabel("original", getInstruction(0)))
        }
        listOf(MainPagerInterceptFingerprint, MainPagerTouchFingerprint).forEach { fingerprint ->
            fingerprint.method.addInstruction(0,
                "invoke-static/range {p0 .. p1}, $EXTENSION->onMainPagerTouch(Ljava/lang/Object;Landroid/view/MotionEvent;)V")
        }
        SettingsStatusLoadFingerprint.method.addInstruction(0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableSwipeLeft()V")
    }
}
