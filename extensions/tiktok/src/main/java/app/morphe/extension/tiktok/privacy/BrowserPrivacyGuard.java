/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.privacy;

import android.net.Uri;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Keeps TikTok's JavaScript bridges on its app pages and off external browser pages.
 *
 * <p>The page is not known when TikTok registers a bridge: {@link WebView#getUrl()} is still null
 * then. Every registration is therefore kept, and the load hooks apply it or withhold it before
 * the next main-frame navigation. A state object is owned by the WebView through an attach-state
 * listener. The static index holds weak references on both sides, so a bridge that points back to
 * its WebView cannot turn the index into a process-lifetime retention cycle.
 */
@SuppressWarnings("unused")
public final class BrowserPrivacyGuard {
    private static final Map<WebView, WeakReference<BridgeState>> STATES = new WeakHashMap<>();

    public static void filterJsInterface(WebView webView, Object object, String name) {
        if (object == null || name == null) {
            webView.addJavascriptInterface(object, name);
            return;
        }

        BridgeState state = stateFor(webView);
        boolean enabled = isEnabled();
        String currentUrl = webView.getUrl();
        boolean allowed = !enabled || (currentUrl == null
                ? state.bridgesAllowed()
                : isTrustedAppPage(currentUrl));
        state.setBridgesAllowed(webView, allowed);
        state.bind(webView, name, object);
    }

    public static void loadUrl(WebView webView, String url) {
        prepareForPage(webView, url);
        webView.loadUrl(url);
    }

    public static void loadUrl(WebView webView, String url, Map<String, String> headers) {
        prepareForPage(webView, url);
        webView.loadUrl(url, headers);
    }

    public static void postUrl(WebView webView, String url, byte[] postData) {
        prepareForPage(webView, url);
        webView.postUrl(url, postData);
    }

    public static void loadData(
            WebView webView, String data, String mimeType, String encoding) {
        prepareForPage(webView, null);
        webView.loadData(data, mimeType, encoding);
    }

    public static void loadDataWithBaseURL(
            WebView webView,
            String baseUrl,
            String data,
            String mimeType,
            String encoding,
            String historyUrl) {
        prepareForPage(webView, baseUrl);
        webView.loadDataWithBaseURL(baseUrl, data, mimeType, encoding, historyUrl);
    }

    public static void reload(WebView webView) {
        prepareForPage(webView, webView.getUrl());
        webView.reload();
    }

    /** Covers a main-frame redirect, which does not call TikTok's {@code loadUrl} again. */
    public static void onPageStarted(WebView webView, String url) {
        prepareForPage(webView, url);
    }

    /** Runs before a string-form WebViewClient navigation continues. */
    public static void onPageRequested(WebView webView, String url) {
        prepareForPage(webView, url);
    }

    /** Runs before a request-form WebViewClient navigation continues. */
    public static void onPageRequested(WebView webView, WebResourceRequest request) {
        if (request == null || request.isForMainFrame()) {
            prepareForPage(webView,
                    request == null || request.getUrl() == null
                            ? null : request.getUrl().toString());
        }
    }

    private static void prepareForPage(WebView webView, String url) {
        if (isScriptUrl(url)) return;

        boolean enabled = isEnabled();
        boolean allowed = !enabled || isTrustedAppPage(url);
        boolean changed = stateFor(webView).setBridgesAllowed(webView, allowed);
        if (enabled && changed) {
            String destination = originLabel(url);
            Logger.printInfo(() -> "Browser privacy guard: JavaScript bridge "
                    + (allowed ? "restored for " : "withheld from ") + destination);
        }
    }

    private static boolean isEnabled() {
        return Utils.getContext() != null && Settings.BLOCK_WEBVIEW_JS_INTERFACES.get();
    }

    static boolean isTrustedAppPage(String url) {
        if (url == null) return false;
        final Uri parsed;
        try {
            parsed = Uri.parse(url);
        } catch (RuntimeException malformed) {
            return false;
        }
        if (!"https".equalsIgnoreCase(parsed.getScheme())) return false;
        String host = parsed.getHost();
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);

        if (host.equals("inapp.tiktokv.com")) return true;
        if (host.equals("pipo-wallet.tiktokv.com")) return true;
        if (host.startsWith("verify-") && host.endsWith(".tiktokv.com")
                && host.substring(0, host.length() - ".tiktokv.com".length()).indexOf('.') < 0) {
            return true;
        }
        if (host.startsWith("shop-") && host.endsWith(".tiktok.com")
                && host.substring(0, host.length() - ".tiktok.com".length()).indexOf('.') < 0) {
            return true;
        }
        if (host.equals("eu.pipopay.com")) return true;
        if (sameOrSubdomain(host, "shop.tiktok.com")
                || sameOrSubdomain(host, "pipopayment.com")
                || sameOrSubdomain(host, "pipopayment.us")) {
            return true;
        }
        String path = parsed.getPath();
        return host.equals("www.tiktok.com") && path != null
                && (path.equals("/verifycenter") || path.startsWith("/verifycenter/"));
    }

    private static boolean sameOrSubdomain(String host, String domain) {
        return host.equals(domain) || host.endsWith('.' + domain);
    }

    private static boolean isScriptUrl(String url) {
        if (url == null) return false;
        int colon = url.indexOf(':');
        return colon > 0 && "javascript".equalsIgnoreCase(url.substring(0, colon));
    }

    private static String originLabel(String url) {
        if (url == null) return "an untrusted local page";
        try {
            Uri parsed = Uri.parse(url);
            String host = parsed.getHost();
            return host == null ? "an untrusted local page" : host.toLowerCase(Locale.ROOT);
        } catch (RuntimeException malformed) {
            return "an invalid page address";
        }
    }

    private static BridgeState stateFor(WebView webView) {
        synchronized (STATES) {
            WeakReference<BridgeState> reference = STATES.get(webView);
            BridgeState state = reference == null ? null : reference.get();
            if (state != null) return state;
            state = new BridgeState();
            webView.addOnAttachStateChangeListener(state);
            STATES.put(webView, new WeakReference<>(state));
            return state;
        }
    }

    static void resetForTests() {
        synchronized (STATES) {
            STATES.clear();
        }
    }

    private static final class BridgeState implements View.OnAttachStateChangeListener {
        private final Map<String, Object> interfaces = new LinkedHashMap<>();
        private boolean bridgesAllowed = true;

        synchronized boolean bridgesAllowed() {
            return bridgesAllowed;
        }

        synchronized void bind(WebView webView, String name, Object object) {
            interfaces.put(name, object);
            if (bridgesAllowed) webView.addJavascriptInterface(object, name);
            else webView.removeJavascriptInterface(name);
        }

        synchronized boolean setBridgesAllowed(WebView webView, boolean allowed) {
            if (bridgesAllowed == allowed) return false;
            bridgesAllowed = allowed;
            for (Map.Entry<String, Object> binding : interfaces.entrySet()) {
                if (allowed) {
                    webView.addJavascriptInterface(binding.getValue(), binding.getKey());
                } else {
                    webView.removeJavascriptInterface(binding.getKey());
                }
            }
            return true;
        }

        @Override public void onViewAttachedToWindow(View view) {}
        @Override public void onViewDetachedFromWindow(View view) {}
    }

    private BrowserPrivacyGuard() {}
}
