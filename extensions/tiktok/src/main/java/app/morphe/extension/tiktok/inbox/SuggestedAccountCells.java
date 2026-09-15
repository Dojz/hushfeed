/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.inbox;

import android.view.View;
import android.view.ViewGroup;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Collapses TikTok's suggestion cells wherever they are bound.
 *
 * <p>"People you may like" has no one source. The inbox, Activity and New followers lists
 * have injectors that {@link InboxControls} answers, but the profile header, the Friends tab
 * and the feed's account cards each build their own list, and every one of those lists is
 * made of cells that extend {@code AbsRecUserCell}. Its bind is the one place they all pass
 * through, and this is called from it with the cell. The cell is a RecyclerView ViewHolder,
 * so its view is the real-named {@code itemView}.
 *
 * <p>The cell is collapsed to nothing rather than only hidden: a GONE child of a horizontal
 * list still holds its width in some layouts. What it was is remembered so a cell recycled
 * after the switch goes off comes back at its own size.
 */
public final class SuggestedAccountCells {
    private static final String HOOK_FAMILY = "suggested accounts";

    /** Width and height, as a cell was laid out before it was collapsed. */
    private static final Map<View, int[]> COLLAPSED = new WeakHashMap<>();

    private SuggestedAccountCells() {
    }

    /** Called at the start of every suggestion cell's bind, with the cell. */
    public static void onBind(Object cell) {
        try {
            Object view = Reflect.readField(cell, "itemView");
            if (!(view instanceof View)) return;
            apply((View) view, Settings.HIDE_INBOX_SUGGESTED_ACCOUNTS.get());
        } catch (Throwable error) {
            Logger.printException(() -> "Could not collapse a suggestion cell", error);
        }
    }

    /** Collapses the view, or puts back what a collapse took, and says which it did. */
    static boolean apply(View view, boolean hide) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        synchronized (COLLAPSED) {
            int[] original = COLLAPSED.get(view);
            if (hide) {
                if (original == null && params != null) {
                    COLLAPSED.put(view, new int[]{params.width, params.height});
                }
                if (params != null) {
                    params.width = 0;
                    params.height = 0;
                    view.setLayoutParams(params);
                }
                view.setVisibility(View.GONE);
                return true;
            }
            if (original == null) return false;
            COLLAPSED.remove(view);
            if (params != null) {
                params.width = original[0];
                params.height = original[1];
                view.setLayoutParams(params);
            }
            view.setVisibility(View.VISIBLE);
            return false;
        }
    }
}
