/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.blockauthor;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.tiktok.feedfilter.SoundIdentity;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.notinterested.NotInterested;
import app.morphe.extension.tiktok.wellbeing.SessionBudget;

import java.lang.ref.WeakReference;

/**
 * The block button that sits on the video player, plus the undo banner shown after a block.
 *
 * Everything is built in code. TikTok resources cannot be compiled by this patch set, so
 * the button is drawn rather than inflated, and it is attached to the activity content
 * root instead of TikTok's own action rail. That keeps it working across builds that
 * reshuffle the player view hierarchy.
 *
 * Long pressing any control enters drag mode so each one can be parked independently. Positions
 * are stored as fractions of the screen, so they survive rotation and a different device.
 */
public final class BlockAuthorOverlay {
    private static final String SOUND_GLYPH = "♪";
    // 44 clears WCAG 2.5.5 and is under Android's own 48dp guidance, and these four have no
    // TouchDelegate to make up the difference. They sit in a column on the feed, where a
    // miss is a like or a follow on somebody's video.
    private static final int BUTTON_SIZE_DP = 48;
    private static final int BUTTON_GAP_DP = 8;
    private static final long UNDO_VISIBLE_MS = 6_000L;

    /** Right edge, just above TikTok's own action rail. */
    private static final float DEFAULT_X_FRACTION = 0.91f;
    private static final float DEFAULT_Y_FRACTION = 0.40f;

    private static WeakReference<View> buttonReference = new WeakReference<>(null);
    private static WeakReference<View> localHideReference = new WeakReference<>(null);
    private static WeakReference<View> soundButtonReference = new WeakReference<>(null);
    private static WeakReference<View> notInterestedReference = new WeakReference<>(null);

    /** Which banner a queued dismiss belongs to. Main thread only. */
    private static int undoGeneration;
    private static WeakReference<ViewGroup> rootReference = new WeakReference<>(null);
    private static ViewTreeObserver.OnGlobalLayoutListener visibilityListener;
    private static WeakReference<View> undoReference = new WeakReference<>(null);

    /** Guards against a double tap blocking, then unblocking, the same account. */
    private static volatile boolean requestInFlight;

    /** True while the user is dragging the button, which suppresses the click. */
    private static boolean dragging;
    private static WeakReference<View> pressedControlReference = new WeakReference<>(null);
    private static float dragOffsetX;
    private static float dragOffsetY;

    private BlockAuthorOverlay() {
    }

    /** Applies changed control settings without waiting for a different creator. */
    public static void refresh() {
        onAuthorChanged(CurrentVideoAuthor.get());
    }

    /** @param author the new current author, or null when the current item has none. */
    static void onAuthorChanged(VideoAuthor author) {
        if (!Settings.BLOCK_AUTHOR_BUTTON.get() && !notInterestedEnabled()) {
            Utils.runOnMainThread(BlockAuthorOverlay::detach);
            return;
        }
        if (author == null) {
            Utils.runOnMainThread(BlockAuthorOverlay::syncVisibility);
            return;
        }
        Utils.runOnMainThread(() -> attach(author));
    }

    /**
     * Shows or hides the button as the feed comes and goes.
     *
     * The button is an overlay on the activity content root, so nothing removes it when
     * the user leaves the video feed. This is the seam that does it.
     */
    public static void setFeedVisible(boolean visible) {
        View button = buttonReference.get();
        if (button == null) {
            return;
        }
        // A control enabled from settings can be attached after a retained hold panel.
        visible = visible && !SessionBudget.isLocked();
        int wanted = visible && Settings.BLOCK_AUTHOR_BUTTON.get() ? View.VISIBLE : View.GONE;
        if (button.getVisibility() != wanted) {
            button.setVisibility(wanted);
            if (!visible) {
                dismissUndo();
            }
        }
        View soundButton = soundButtonReference.get();
        if (soundButton != null) {
            // The sound button needs a sound to act on, and a feed filter to act through.
            boolean soundWanted = visible && Settings.BLOCK_AUTHOR_BUTTON.get()
                    && Settings.BLOCK_SOUND_BUTTON.get() && SettingsStatus.feedFilterEnabled
                    && CurrentVideoSound.get() != null && CurrentVideoSound.get().isUsable();
            int soundVisibility = soundWanted ? View.VISIBLE : View.GONE;
            if (soundButton.getVisibility() != soundVisibility) {
                soundButton.setVisibility(soundVisibility);
            }
        }
        View localHide = localHideReference.get();
        if (localHide != null) {
            boolean localWanted = visible && Settings.BLOCK_AUTHOR_BUTTON.get()
                    && Settings.LOCAL_HIDE_BUTTON.get()
                    && SettingsStatus.feedFilterEnabled;
            int localVisibility = localWanted ? View.VISIBLE : View.GONE;
            if (localHide.getVisibility() != localVisibility) {
                localHide.setVisibility(localVisibility);
            }
        }
        View feedback = notInterestedReference.get();
        if (feedback != null) feedback.setVisibility(visible && notInterestedEnabled() ? View.VISIBLE : View.GONE);
    }

    /**
     * Re-checks the cached feed selection and the active hold on each layout pass.
     */
    private static void syncVisibility() {
        Activity activity = Utils.getActivity();
        if (activity == null) {
            return;
        }
        setFeedVisible(FeedVisibility.isOnFeed(activity)
                && !FeedVisibility.isCommentSheetVisible(activity)
                && CurrentVideoAuthor.get() != null);
    }

    private static void attach(VideoAuthor author) {
        try {
            Activity activity = Utils.getActivity();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                return;
            }

            ViewGroup root = activity.findViewById(android.R.id.content);
            if (root == null) {
                return;
            }

            View existing = buttonReference.get();
            if (existing != null && existing.getParent() == root) {
                syncVisibility();
                return;
            }

            final View button = createButton(activity);
            final int size = SettingsUi.dp(activity, BUTTON_SIZE_DP);
            // Absolute LEFT, not START. Every position here is a pixel worked out from a raw
            // touch and written to leftMargin, and a mirrored layout resolves START to RIGHT and
            // then reads rightMargin, which nothing sets: the saved position was discarded, a
            // drag moved nothing sideways, and the Not interested button landed on top of the
            // block button because the two differ only in leftMargin.
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    size, size, Gravity.TOP | Gravity.LEFT);
            button.setLayoutParams(params);

            root.addView(button);
            buttonReference = new WeakReference<>(button);

            final View localHide = createLocalHideButton(activity);
            localHide.setLayoutParams(new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.LEFT));
            root.addView(localHide);
            localHideReference = new WeakReference<>(localHide);

            final View soundButton = createSoundButton(activity);
            soundButton.setLayoutParams(new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.LEFT));
            root.addView(soundButton);
            soundButtonReference = new WeakReference<>(soundButton);
            View feedback = createNotInterestedButton(activity);
            feedback.setLayoutParams(new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.LEFT));
            root.addView(feedback);
            notInterestedReference = new WeakReference<>(feedback);

            // The root has no measured size until it lays out, so the saved fraction can
            // only be turned into margins once dimensions are known.
            root.post(() -> {
                if (root.getWidth() == 0 || root.getHeight() == 0) return;
                applySavedPosition(button, root, size, Settings.BLOCK_AUTHOR_BUTTON_POSITION,
                        DEFAULT_X_FRACTION, DEFAULT_Y_FRACTION);
                int step = size + SettingsUi.dp(activity, BUTTON_GAP_DP);
                float blockX = DEFAULT_X_FRACTION * root.getWidth();
                float blockY = DEFAULT_Y_FRACTION * root.getHeight();
                applySavedPosition(localHide, root, size, Settings.LOCAL_HIDE_BUTTON_POSITION,
                        blockX / root.getWidth(), (blockY + step) / root.getHeight());
                applySavedPosition(soundButton, root, size, Settings.BLOCK_SOUND_BUTTON_POSITION,
                        blockX / root.getWidth(),
                        (blockY + step * (Settings.LOCAL_HIDE_BUTTON.get() ? 2f : 1f))
                                / root.getHeight());
                applySavedPosition(feedback, root, size, Settings.NOT_INTERESTED_BUTTON_POSITION,
                        (blockX - step) / root.getWidth(), blockY / root.getHeight());
            });

            installVisibilityListener(root);
            syncVisibility();

            Logger.printDebug(() -> "Block button attached for " + author.label());
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not attach the block button", ex);
        }
    }

    private static void installVisibilityListener(ViewGroup root) {
        if (visibilityListener != null && rootReference.get() == root) {
            return;
        }
        removeVisibilityListener();

        visibilityListener = BlockAuthorOverlay::syncVisibility;
        root.getViewTreeObserver().addOnGlobalLayoutListener(visibilityListener);
        rootReference = new WeakReference<>(root);
    }

    private static void removeVisibilityListener() {
        ViewGroup root = rootReference.get();
        if (root != null && visibilityListener != null) {
            root.getViewTreeObserver().removeOnGlobalLayoutListener(visibilityListener);
        }
        visibilityListener = null;
        rootReference = new WeakReference<>(null);
    }

    private static void detach() {
        removeVisibilityListener();
        resetDragState();
        View button = buttonReference.get();
        if (button != null && button.getParent() instanceof ViewGroup) {
            ((ViewGroup) button.getParent()).removeView(button);
        }
        buttonReference = new WeakReference<>(null);
        View soundButton = soundButtonReference.get();
        if (soundButton != null && soundButton.getParent() instanceof ViewGroup) {
            ((ViewGroup) soundButton.getParent()).removeView(soundButton);
        }
        soundButtonReference = new WeakReference<>(null);
        View localHide = localHideReference.get();
        if (localHide != null && localHide.getParent() instanceof ViewGroup) {
            ((ViewGroup) localHide.getParent()).removeView(localHide);
        }
        localHideReference = new WeakReference<>(null);
        View feedback = notInterestedReference.get();
        if (feedback != null && feedback.getParent() instanceof ViewGroup) {
            ((ViewGroup) feedback.getParent()).removeView(feedback);
        }
        notInterestedReference = new WeakReference<>(null);
        dismissUndo();
    }

    private static View createSoundButton(Activity activity) {
        TextView button = new TextView(activity);
        button.setText(SOUND_GLYPH);
        button.setTextColor(Color.WHITE);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(L10n.t(activity, "Block this sound"));

        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(Color.argb(140, 0, 0, 0));
        background.setStroke(SettingsUi.dp(activity, 1), Color.argb(90, 255, 255, 255));
        button.setBackground(background);

        button.setOnClickListener(view -> onBlockSoundTapped());
        installDrag(button);
        return button;
    }

    private static View createLocalHideButton(Activity activity) {
        TextView button = new TextView(activity);
        button.setText("×");
        button.setTextColor(Color.WHITE);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(L10n.t(activity, "Hide this creator locally"));

        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(Color.argb(140, 0, 0, 0));
        background.setStroke(SettingsUi.dp(activity, 1), Color.argb(90, 255, 255, 255));
        button.setBackground(background);
        button.setOnClickListener(view -> onLocalHideTapped());
        installDrag(button);
        return button;
    }

    private static boolean notInterestedEnabled() {
        return SettingsStatus.notInterestedEnabled && Settings.NOT_INTERESTED_BUTTON.get();
    }

    private static View createNotInterestedButton(Activity activity) {
        TextView button = new TextView(activity);
        button.setText("-");
        button.setTextColor(Color.WHITE);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(L10n.t(activity, "Not interested in this video"));
        // The same round shape and the same scrim as the three it shares the rail with. It was
        // a rounded rectangle over a darker scrim, which on a column of four reads as a mistake
        // rather than as a distinction.
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(Color.argb(140, 0, 0, 0));
        background.setStroke(SettingsUi.dp(activity, 1), Color.argb(90, 255, 255, 255));
        button.setBackground(background);
        button.setOnClickListener(view -> NotInterested.submit());
        installDrag(button);
        return button;
    }

    /**
     * Records the current sound so the feed filter skips every video that uses it. Ids
     * are exact; a sound with no id is recorded by name, which also catches re-uploads.
     */
    private static void onBlockSoundTapped() {
        CurrentVideoSound sound = CurrentVideoSound.get();
        if (sound == null || !sound.isUsable()) {
            Utils.showToastShort(L10n.t("No sound to block on this video"));
            return;
        }

        final boolean byId = sound.id != null && !sound.id.isEmpty();
        if (byId) {
            Settings.BLOCKED_SOUND_IDS.save(SoundIdentity.withEntry(Settings.BLOCKED_SOUND_IDS.get(), sound.id));
        } else {
            Settings.BLOCKED_SOUND_NAMES.save(SoundIdentity.withEntry(Settings.BLOCKED_SOUND_NAMES.get(), sound.name));
        }
        Logger.printDebug(() -> "Blocked sound " + sound.label() + (byId ? " by id" : " by name"));

        showUndoBanner(L10n.f("Skipping videos with %1$s", sound.label()), () -> {
            if (byId) {
                Settings.BLOCKED_SOUND_IDS.save(SoundIdentity.withoutEntry(Settings.BLOCKED_SOUND_IDS.get(), sound.id));
            } else {
                Settings.BLOCKED_SOUND_NAMES.save(SoundIdentity.withoutEntry(Settings.BLOCKED_SOUND_NAMES.get(), sound.name));
            }
            Utils.showToastShort(L10n.f("Unblocked %1$s", sound.label()));
        });
    }

    private static View createButton(Activity activity) {
        TextView button = new TextView(activity);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(L10n.t(activity, "Block this account"));

        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(Color.argb(140, 0, 0, 0));
        background.setStroke(SettingsUi.dp(activity, 1), Color.argb(90, 255, 255, 255));

        // The symbol is drawn over the disc instead of set as text, because the font
        // TikTok happens to be using may not carry it.
        Drawable glyph = new BlockGlyphDrawable(Color.WHITE, SettingsUi.dp(activity, 2));
        button.setBackground(new LayerDrawable(new Drawable[]{background, glyph}));

        button.setOnClickListener(view -> onBlockTapped());
        installDrag(button);
        return button;
    }

    /** Gives every feed control the same independent long-press drag behavior. */
    private static void installDrag(View button) {
        button.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(
                    View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(android.widget.Button.class.getName());
                // Pointer drag has a release event. A standalone accessibility long-click does
                // not, so do not advertise an action that cannot complete the gesture.
                info.setLongClickable(false);
                info.removeAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
                        .ACTION_LONG_CLICK);
            }

            @Override public boolean performAccessibilityAction(View host, int action,
                    android.os.Bundle arguments) {
                if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK) {
                    return false;
                }
                return super.performAccessibilityAction(host, action, arguments);
            }
        });
        button.setOnLongClickListener(view -> {
            // Accessibility ACTION_LONG_CLICK has no pointer down or release. Entering raw drag
            // mode from it left every later click stuck behind a gesture that could never finish.
            if (pressedControlReference.get() != view) return false;
            dragging = true;
            view.setAlpha(0.75f);
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            Utils.showToastShort(L10n.t("Drag to move, release to place"));
            return true;
        });

        button.setOnTouchListener(BlockAuthorOverlay::onButtonTouch);
    }

    /**
     * Handles dragging. Returns false unless a drag is in progress so that normal click
     * and long press handling is left alone.
     */
    private static boolean onButtonTouch(View view, MotionEvent event) {
        if (!dragging) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                pressedControlReference = new WeakReference<>(view);
                dragOffsetX = event.getX();
                dragOffsetY = event.getY();
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                pressedControlReference = new WeakReference<>(null);
            }
            return false;
        }

        ViewGroup parent = view.getParent() instanceof ViewGroup
                ? (ViewGroup) view.getParent()
                : null;
        if (parent == null) {
            resetDragState();
            return false;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                float left = event.getRawX() - dragOffsetX - parentLeft(parent);
                float top = event.getRawY() - dragOffsetY - parentTop(parent);
                moveTo(view, parent, left, top);
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                savePosition(view, parent);
                resetDragState();
                return true;
            }

            default:
                return true;
        }
    }

    private static void resetDragState() {
        View pressed = pressedControlReference.get();
        if (pressed != null) pressed.setAlpha(1f);
        dragging = false;
        pressedControlReference = new WeakReference<>(null);
    }

    private static int parentLeft(ViewGroup parent) {
        int[] location = new int[2];
        parent.getLocationOnScreen(location);
        return location[0];
    }

    private static int parentTop(ViewGroup parent) {
        int[] location = new int[2];
        parent.getLocationOnScreen(location);
        return location[1];
    }

    /** Moves the button, keeping it fully inside its parent. */
    private static void moveTo(View view, ViewGroup parent, float left, float top) {
        // Before the first layout the view has no size, so fall back to the size it was
        // given, or the clamp would let it sit partly off the right and bottom edges.
        ViewGroup.LayoutParams layout = view.getLayoutParams();
        int width = view.getWidth() > 0 ? view.getWidth() : layout.width;
        int height = view.getHeight() > 0 ? view.getHeight() : layout.height;
        int maxLeft = Math.max(0, parent.getWidth() - width);
        int maxTop = Math.max(0, parent.getHeight() - height);

        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        params.leftMargin = Math.round(Math.min(Math.max(left, 0), maxLeft));
        params.topMargin = Math.round(Math.min(Math.max(top, 0), maxTop));
        view.setLayoutParams(params);
    }

    private static void applySavedPosition(
            View view,
            ViewGroup parent,
            int size,
            StringSetting setting,
            float defaultX,
            float defaultY
    ) {
        if (parent.getWidth() == 0 || parent.getHeight() == 0) {
            return;
        }

        float[] fractions = loadPositionFractions(setting, defaultX, defaultY);
        float left = fractions[0] * parent.getWidth() - size / 2f;
        float top = fractions[1] * parent.getHeight() - size / 2f;
        moveTo(view, parent, left, top);
    }

    /** @return the stored centre position as {x, y} fractions of the parent. */
    private static float[] loadPositionFractions(
            StringSetting setting,
            float defaultX,
            float defaultY
    ) {
        String stored = setting.get();
        if (stored != null && !stored.isEmpty()) {
            String[] parts = stored.split(",");
            if (parts.length == 2) {
                try {
                    float x = Float.parseFloat(parts[0].trim());
                    float y = Float.parseFloat(parts[1].trim());
                    if (x >= 0f && x <= 1f && y >= 0f && y <= 1f) {
                        return new float[]{x, y};
                    }
                } catch (NumberFormatException ignored) {
                    // Fall through to the default.
                }
            }
        }
        return new float[]{clampFraction(defaultX), clampFraction(defaultY)};
    }

    private static void savePosition(View view, ViewGroup parent) {
        if (parent.getWidth() == 0 || parent.getHeight() == 0) {
            return;
        }

        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        float x = (params.leftMargin + view.getWidth() / 2f) / parent.getWidth();
        float y = (params.topMargin + view.getHeight() / 2f) / parent.getHeight();

        StringSetting setting = positionSetting(view);
        String position = round(x) + "," + round(y);
        setting.save(position);
        Logger.printDebug(() -> String.valueOf(view.getContentDescription()) + " moved to " + position);
    }

    private static StringSetting positionSetting(View view) {
        if (view == localHideReference.get()) return Settings.LOCAL_HIDE_BUTTON_POSITION;
        if (view == soundButtonReference.get()) return Settings.BLOCK_SOUND_BUTTON_POSITION;
        if (view == notInterestedReference.get()) return Settings.NOT_INTERESTED_BUTTON_POSITION;
        return Settings.BLOCK_AUTHOR_BUTTON_POSITION;
    }

    private static float clampFraction(float value) {
        return Math.min(Math.max(value, 0f), 1f);
    }

    private static String round(float value) {
        return String.valueOf(Math.round(value * 1000f) / 1000f);
    }

    private static void onBlockTapped() {
        if (requestInFlight) {
            return;
        }

        VideoAuthor author = CurrentVideoAuthor.get();
        if (author == null || !author.isUsable()) {
            Utils.showToastShort(L10n.t("No account to block on this video"));
            return;
        }

        requestInFlight = true;
        setButtonEnabled(false);

        BlockAuthorService.block(author, (result, message) -> {
            requestInFlight = false;
            setButtonEnabled(true);

            if (result == BlockAuthorService.Result.CONFIRMED) {
                showUndo(author);
            } else if (result == BlockAuthorService.Result.UNCONFIRMED) {
                Utils.showToastLong(L10n.f("Could not confirm block for %1$s", author.label()));
            } else {
                Utils.showToastLong(message == null || message.isEmpty()
                        ? L10n.f("Could not block %1$s", author.label())
                        : L10n.f("Could not block %1$s: %2$s", author.label(), message));
            }
        });
    }

    private static void onLocalHideTapped() {
        VideoAuthor author = CurrentVideoAuthor.get();
        if (author == null || !author.isUsable() || author.stableId() == null
                || author.stableId().isEmpty()) {
            Utils.showToastShort(L10n.t("No account to hide on this video"));
            return;
        }

        String before = Settings.LOCAL_HIDDEN_CREATORS.get();
        String after = app.morphe.extension.tiktok.feedfilter.AdvancedFeedRules.addCreatorEntry(
                before, author.stableId());
        if (after.equals(before)) {
            Utils.showToastShort(L10n.t("That creator is already in the list"));
            return;
        }
        Settings.LOCAL_HIDDEN_CREATORS.save(after);
        showUndoBanner(L10n.f("Hidden %1$s locally", author.label()), () -> {
            Settings.LOCAL_HIDDEN_CREATORS.save(before);
            Utils.showToastShort(L10n.f("Showing %1$s again", author.label()));
        });
    }

    private static void setButtonEnabled(boolean enabled) {
        View button = buttonReference.get();
        if (button != null) {
            button.setEnabled(enabled);
            button.setAlpha(enabled ? 1f : 0.4f);
        }
    }

    /**
     * Blocking is immediate and has no confirmation, so the undo banner is the safety net
     * for a mis-tap while scrolling.
     */
    private static void showUndo(VideoAuthor author) {
        showUndoBanner(L10n.f("Blocked %1$s", author.label()),
                () -> BlockAuthorService.unblock(author,
                        (result, message) -> Utils.showToastShort(result == BlockAuthorService.Result.CONFIRMED
                                ? L10n.f("Unblocked %1$s", author.label())
                                : result == BlockAuthorService.Result.UNCONFIRMED
                                ? L10n.f("Could not confirm unblock for %1$s", author.label())
                                : L10n.f("Could not unblock %1$s", author.label()))));
    }

    /**
     * Shows a message with an UNDO action for six seconds, over whatever activity is on
     * screen. Falls back to a plain toast when there is nowhere to draw it.
     */
    public static void showUndoBanner(String message, Runnable undoAction) {
        Utils.runOnMainThread(() -> {
            Activity activity = Utils.getActivity();
            ViewGroup root = activity == null || activity.isFinishing() || activity.isDestroyed()
                    ? null : activity.findViewById(android.R.id.content);
            showUndoBanner(root, message, undoAction);
        });
    }

    /**
     * Shows a message with an UNDO action for six seconds inside {@code root}, which
     * should be the window the user is looking at. A banner added to the activity's
     * content root is invisible under a panel that has its own window, which is why the
     * root is a parameter. Falls back to a plain toast when there is nowhere to draw it.
     */
    public static void showUndoBanner(ViewGroup root, String message, Runnable undoAction) {
        showBanner(root, message, undoAction);
    }

    /**
     * The same banner with nothing to press, for anything that only has something to say.
     *
     * <p>Everything worth having is in the shape rather than in the Undo: it takes no focus,
     * takes itself away after six seconds, and announces itself once as a polite live region,
     * which a toast does not.
     */
    public static void showNoticeBanner(ViewGroup root, String message) {
        showBanner(root, message, null);
    }

    private static void showBanner(ViewGroup root, String message, Runnable undoAction) {
        Utils.runOnMainThread(() -> {
            try {
                if (root == null) {
                    Utils.showToastShort(message);
                    return;
                }
                Activity activity = Utils.getActivity();
                if (activity == null) {
                    Utils.showToastShort(message);
                    return;
                }

                dismissUndo();

                LinearLayout banner = new LinearLayout(activity);
                banner.setOrientation(LinearLayout.HORIZONTAL);
                banner.setGravity(Gravity.CENTER_VERTICAL);
                banner.setPadding(SettingsUi.dp(activity, 16), SettingsUi.dp(activity, 12), SettingsUi.dp(activity, 16), SettingsUi.dp(activity, 12));

                GradientDrawable background = new GradientDrawable();
                background.setCornerRadius(SettingsUi.dp(activity, 10));
                background.setColor(Color.argb(235, 28, 28, 30));
                banner.setBackground(background);

                TextView label = new TextView(activity);
                label.setText(message);
                label.setTextColor(Color.WHITE);
                label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                banner.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));

                if (undoAction != null) addUndo(activity, banner, undoAction);
                // Nothing announced this banner, so a reader using TalkBack never knew there
                // was a way back at all.
                banner.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

                // Every window decor is a FrameLayout, so gravity params work in any root.
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2,
                        Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                params.setMargins(SettingsUi.dp(activity, 16), 0, SettingsUi.dp(activity, 16), SettingsUi.dp(activity, 96));
                banner.setLayoutParams(params);

                root.addView(banner);
                undoReference = new WeakReference<>(banner);

                // A second banner inside the six seconds replaced the first, and the first
                // banner's dismiss was still queued: it took the new banner away early, with
                // its Undo. Nothing here can cancel a posted runnable, so each dismiss checks
                // whether it is still the one that was scheduled.
                final int token = ++undoGeneration;
                Utils.runOnMainThreadDelayed(() -> {
                    if (token == undoGeneration) dismissUndo();
                }, UNDO_VISIBLE_MS);
            } catch (Throwable ex) {
                Logger.printException(() -> "Could not show the undo banner", ex);
                Utils.showToastShort(message);
            }
        });
    }

    private static void addUndo(Activity activity, LinearLayout banner, Runnable undoAction) {
        TextView undo = new TextView(activity);
        undo.setText(L10n.t(activity, "Undo"));
        undo.setContentDescription(L10n.t(activity, "Undo"));
        undo.setTextColor(SettingsUi.OVERLAY_ACCENT);
        undo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        // A banner that dismisses itself is the worst place for a small target.
        undo.setPadding(SettingsUi.dp(activity, 16), SettingsUi.dp(activity, 12),
                SettingsUi.dp(activity, 16), SettingsUi.dp(activity, 12));
        undo.setMinimumHeight(SettingsUi.dp(activity, 48));
        undo.setMinimumWidth(SettingsUi.dp(activity, 48));
        undo.setGravity(Gravity.CENTER);
        SettingsUi.markAsButton(undo);
        undo.setOnClickListener(view -> {
            dismissUndo();
            undoAction.run();
        });
        banner.addView(undo, new LinearLayout.LayoutParams(-2, -2));
    }

    private static void dismissUndo() {
        undoGeneration++;
        View banner = undoReference.get();
        if (banner != null && banner.getParent() instanceof ViewGroup) {
            ((ViewGroup) banner.getParent()).removeView(banner);
        }
        undoReference = new WeakReference<>(null);
    }

}
