package app.morphe.extension.tiktok.blockauthor;

import static org.junit.Assert.*;

import android.app.Activity;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/** Temporary review probe. Delete. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class ZzProbeFeedVisibilityTest {

    /** A tab fully on screen, overhanging an ancestor that does not clip its children. */
    @Test public void probeNonClippingAncestor() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            FrameLayout root = new FrameLayout(activity);
            // A zero-height anchor container that draws its child outside itself.
            FrameLayout anchor = new FrameLayout(activity);
            anchor.setClipChildren(false);
            View homeTab = new View(activity);
            homeTab.setId(0x7f0a4b89);
            homeTab.setSelected(true);
            anchor.addView(homeTab, new FrameLayout.LayoutParams(60, 40));
            root.addView(anchor, new FrameLayout.LayoutParams(0, 0, Gravity.BOTTOM));
            root.setClipChildren(false);
            activity.setContentView(root);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            FeedVisibility.resolveForTests(activity.getPackageName(), "o1k", homeTab.getId());
            System.out.println("PROBE clipChildren tab=" + homeTab.getWidth() + "x" + homeTab.getHeight()
                    + " anchor=" + anchor.getWidth() + "x" + anchor.getHeight()
                    + " shown=" + homeTab.isShown()
                    + " isOnFeed=" + FeedVisibility.isOnFeed(activity)
                    + " onRecommendationFeed=" + FeedVisibility.onRecommendationFeed(activity));
            FeedVisibility.resolveForTests(activity.getPackageName(), "o1k", 0);
        }
    }

    /** Right-to-left: the same tree with the layout direction mirrored. */
    @Test public void probeRtl() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            FrameLayout pager = new FrameLayout(activity);
            pager.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            FrameLayout feedPage = new FrameLayout(activity);
            View homeTab = new View(activity);
            homeTab.setId(0x7f0a4b89);
            homeTab.setSelected(true);
            feedPage.addView(homeTab, new FrameLayout.LayoutParams(60, 40, Gravity.BOTTOM));
            pager.addView(feedPage, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            activity.setContentView(pager);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            FeedVisibility.resolveForTests(activity.getPackageName(), "o1k", homeTab.getId());
            System.out.println("PROBE rtl rest isOnFeed=" + FeedVisibility.isOnFeed(activity));
            // An RTL pager scrolls the other way.
            pager.scrollTo(-pager.getWidth(), 0);
            System.out.println("PROBE rtl scrolled isOnFeed=" + FeedVisibility.isOnFeed(activity)
                    + " onRecommendationFeed=" + FeedVisibility.onRecommendationFeed(activity));
            FeedVisibility.resolveForTests(activity.getPackageName(), "o1k", 0);
        }
    }

    /** A story pager left attached and VISIBLE but translated off screen after a dismissal. */
    @Test public void probeDismissedStoryStillTranslatedOffScreen() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            FrameLayout root = new FrameLayout(activity);
            View detail = new View(activity);
            root.addView(detail, new FrameLayout.LayoutParams(400, 600));
            FrameLayout storyPager = new FrameLayout(activity);
            storyPager.setId(0x7f0a7001);
            root.addView(storyPager, new FrameLayout.LayoutParams(400, 600));
            activity.setContentView(root);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            FeedVisibility.resolveForTests(activity.getPackageName(), "o1k", 0);
            FeedVisibility.resolveForTests(activity.getPackageName(), "vp_story_collection",
                    storyPager.getId());
            Object page = new Object();
            FeedVisibility.onDetailView(page, detail);
            FeedVisibility.onDetailResume(page);
            try {
                // Dismissed by a swipe down that leaves the pager attached, VISIBLE, off screen.
                storyPager.setTranslationY(5000f);
                System.out.println("PROBE dismissed story shown=" + storyPager.isShown()
                        + " isStoryVisible=" + FeedVisibility.isStoryVisible(activity));
            } finally {
                FeedVisibility.onDetailDestroyed(page);
                FeedVisibility.resolveForTests(activity.getPackageName(), "vp_story_collection", 0);
            }
        }
    }
}
