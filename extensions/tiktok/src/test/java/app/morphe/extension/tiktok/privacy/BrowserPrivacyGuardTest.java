/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.privacy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowWebView;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class BrowserPrivacyGuardTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        Settings.BLOCK_WEBVIEW_JS_INTERFACES.save(false);
        BrowserPrivacyGuard.resetForTests();
    }

    @After public void tearDown() {
        Settings.BLOCK_WEBVIEW_JS_INTERFACES.save(false);
        BrowserPrivacyGuard.resetForTests();
    }

    @Test public void onlyNamedFirstPartyAppPagesKeepTheBridge() {
        assertTrue(BrowserPrivacyGuard.isTrustedAppPage(
                "https://inapp.tiktokv.com/tpp/inapp/pns_product_activity_center/ac/watch_history"));
        assertTrue(BrowserPrivacyGuard.isTrustedAppPage(
                "https://verify-sg.tiktokv.com/captcha/verify"));
        assertTrue(BrowserPrivacyGuard.isTrustedAppPage(
                "https://checkout.pipopayment.com/pay"));
        assertTrue(BrowserPrivacyGuard.isTrustedAppPage(
                "https://fp.pipopayment.us/device"));
        assertTrue(BrowserPrivacyGuard.isTrustedAppPage(
                "https://pipo-wallet.tiktokv.com/wallet"));
        assertTrue(BrowserPrivacyGuard.isTrustedAppPage(
                "https://eu.pipopay.com/checkout"));
        assertTrue(BrowserPrivacyGuard.isTrustedAppPage(
                "https://shop-sg.tiktok.com/order"));
        assertTrue(BrowserPrivacyGuard.isTrustedAppPage(
                "https://www.tiktok.com/verifycenter/authentication"));
        assertTrue(BrowserPrivacyGuard.isTrustedAppPage(
                "https://www.tiktok.com/verifycenter/ttcaptcha/"));

        assertFalse(BrowserPrivacyGuard.isTrustedAppPage(
                "https://www.tiktoklinksafety.us/link/?target=example.com"));
        assertFalse(BrowserPrivacyGuard.isTrustedAppPage("https://example.com"));
        assertFalse(BrowserPrivacyGuard.isTrustedAppPage(
                "https://inapp.tiktokv.com.example.com/watch_history"));
        assertFalse(BrowserPrivacyGuard.isTrustedAppPage(
                "https://shop-sg.tiktok.com.example.com/order"));
        assertFalse(BrowserPrivacyGuard.isTrustedAppPage(
                "https://shop-sg.evil.tiktok.com/order"));
        assertFalse(BrowserPrivacyGuard.isTrustedAppPage(
                "https://us.pipopay.com/checkout"));
        assertFalse(BrowserPrivacyGuard.isTrustedAppPage(
                "http://inapp.tiktokv.com/watch_history"));
        assertFalse(BrowserPrivacyGuard.isTrustedAppPage("javascript:alert(1)"));
        assertFalse(BrowserPrivacyGuard.isTrustedAppPage(null));
    }

    @Test public void externalLoadsRemoveEveryBridgeAndFirstPartyLoadsRestoreThem() {
        Settings.BLOCK_WEBVIEW_JS_INTERFACES.save(true);
        WebView webView = new WebView(context);
        ShadowWebView shadow = Shadows.shadowOf(webView);
        Object first = new Object();
        Object second = new Object();

        BrowserPrivacyGuard.filterJsInterface(webView, first, "first");
        BrowserPrivacyGuard.filterJsInterface(webView, second, "second");
        assertSame(first, shadow.getJavascriptInterface("first"));
        assertSame(second, shadow.getJavascriptInterface("second"));

        BrowserPrivacyGuard.loadUrl(webView, "https://www.tiktoklinksafety.us/link/");
        assertNull(shadow.getJavascriptInterface("first"));
        assertNull(shadow.getJavascriptInterface("second"));
        assertEquals("https://www.tiktoklinksafety.us/link/", shadow.getLastLoadedUrl());

        BrowserPrivacyGuard.loadUrl(webView,
                "https://inapp.tiktokv.com/tpp/inapp/pns_product_activity_center/ac/watch_history");
        assertSame(first, shadow.getJavascriptInterface("first"));
        assertSame(second, shadow.getJavascriptInterface("second"));
    }

    @Test public void redirectCallbacksChangeTheNextPagePolicy() {
        Settings.BLOCK_WEBVIEW_JS_INTERFACES.save(true);
        WebView webView = new WebView(context);
        ShadowWebView shadow = Shadows.shadowOf(webView);
        Object bridge = new Object();
        BrowserPrivacyGuard.filterJsInterface(webView, bridge, "bridge");

        BrowserPrivacyGuard.onPageStarted(webView, "https://example.com/landing");
        assertNull(shadow.getJavascriptInterface("bridge"));
        BrowserPrivacyGuard.onPageStarted(webView, "https://verify-va.tiktokv.com/captcha");
        assertSame(bridge, shadow.getJavascriptInterface("bridge"));
    }

    @Test public void subframeRequestsCannotChangeTheMainPagePolicy() {
        Settings.BLOCK_WEBVIEW_JS_INTERFACES.save(true);
        WebView webView = new WebView(context);
        ShadowWebView shadow = Shadows.shadowOf(webView);
        Object bridge = new Object();
        BrowserPrivacyGuard.filterJsInterface(webView, bridge, "bridge");

        BrowserPrivacyGuard.onPageStarted(webView, "https://example.com/landing");
        assertNull(shadow.getJavascriptInterface("bridge"));
        BrowserPrivacyGuard.onPageRequested(webView,
                request("https://inapp.tiktokv.com/embedded", false));
        assertNull(shadow.getJavascriptInterface("bridge"));

        BrowserPrivacyGuard.onPageRequested(webView,
                request("https://inapp.tiktokv.com/activity", true));
        assertSame(bridge, shadow.getJavascriptInterface("bridge"));
    }

    @Test public void scriptLoadsKeepTheCurrentPagePolicy() {
        Settings.BLOCK_WEBVIEW_JS_INTERFACES.save(true);
        WebView webView = new WebView(context);
        ShadowWebView shadow = Shadows.shadowOf(webView);
        Object bridge = new Object();
        BrowserPrivacyGuard.filterJsInterface(webView, bridge, "bridge");

        BrowserPrivacyGuard.onPageStarted(webView, "https://example.com/landing");
        BrowserPrivacyGuard.loadUrl(webView, "javascript:document.title='still external'");
        assertNull(shadow.getJavascriptInterface("bridge"));

        BrowserPrivacyGuard.onPageStarted(webView, "https://inapp.tiktokv.com/activity");
        BrowserPrivacyGuard.loadUrl(webView, "JAVASCRIPT:document.title='still trusted'");
        assertSame(bridge, shadow.getJavascriptInterface("bridge"));
    }

    @Test public void turningTheSwitchOffLeavesNativeRegistrationAndNavigationAlone() {
        WebView webView = new WebView(context);
        ShadowWebView shadow = Shadows.shadowOf(webView);
        Object bridge = new Object();
        BrowserPrivacyGuard.filterJsInterface(webView, bridge, "bridge");

        BrowserPrivacyGuard.loadUrl(webView, "https://example.com", Map.of("X-Test", "yes"));
        assertSame(bridge, shadow.getJavascriptInterface("bridge"));
        assertEquals("https://example.com", shadow.getLastLoadedUrl());
        assertEquals("yes", shadow.getLastAdditionalHttpHeaders().get("X-Test"));
    }

    @Test public void dataLoadsUseTheirBaseOriginAndPlainDataFailsClosed() {
        Settings.BLOCK_WEBVIEW_JS_INTERFACES.save(true);
        WebView webView = new WebView(context);
        ShadowWebView shadow = Shadows.shadowOf(webView);
        Object bridge = new Object();
        BrowserPrivacyGuard.filterJsInterface(webView, bridge, "bridge");

        BrowserPrivacyGuard.loadDataWithBaseURL(webView, "https://inapp.tiktokv.com/app/",
                "<p>inside</p>", "text/html", "UTF-8", null);
        assertSame(bridge, shadow.getJavascriptInterface("bridge"));

        BrowserPrivacyGuard.loadData(webView, "<p>unknown origin</p>", "text/html", "UTF-8");
        assertNull(shadow.getJavascriptInterface("bridge"));
    }

    @Test public void theStaticRegistryCannotOwnAWebViewThroughItsBridge() {
        Settings.BLOCK_WEBVIEW_JS_INTERFACES.save(true);
        WeakReference<WebView> discarded = abandonExternalWebView();
        for (int attempt = 0; attempt < 20 && discarded.get() != null; attempt++) {
            System.gc();
            System.runFinalization();
        }
        assertNull("the bridge registry retained a discarded WebView", discarded.get());
    }

    private WeakReference<WebView> abandonExternalWebView() {
        WebView webView = new WebView(context);
        BrowserPrivacyGuard.filterJsInterface(webView, new BridgeThatOwnsItsView(webView), "bridge");
        BrowserPrivacyGuard.onPageStarted(webView, "https://example.com");
        return new WeakReference<>(webView);
    }

    private static WebResourceRequest request(String url, boolean mainFrame) {
        return new WebResourceRequest() {
            @Override public Uri getUrl() {
                return Uri.parse(url);
            }

            @Override public boolean isForMainFrame() {
                return mainFrame;
            }

            @Override public boolean isRedirect() {
                return false;
            }

            @Override public boolean hasGesture() {
                return false;
            }

            @Override public String getMethod() {
                return "GET";
            }

            @Override public Map<String, String> getRequestHeaders() {
                return Collections.emptyMap();
            }
        };
    }

    private static final class BridgeThatOwnsItsView {
        @SuppressWarnings("unused")
        final WebView webView;

        BridgeThatOwnsItsView(WebView webView) {
            this.webView = webView;
        }
    }
}
