/* -*- Mode: Java; c-basic-offset: 4; tab-width: 20; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package org.mozilla.focus.webview;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.AsyncTask;
import android.support.annotation.WorkerThread;

import com.amazon.android.webkit.AmazonWebResourceResponse;
import com.amazon.android.webkit.AmazonWebView;
import com.amazon.android.webkit.AmazonWebViewClient;

import org.mozilla.focus.R;
import org.mozilla.focus.utils.Settings;
import org.mozilla.focus.iwebview.IWebView;
import org.mozilla.focus.webview.matcher.UrlMatcher;

public class TrackingProtectionWebViewClient extends AmazonWebViewClient
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static volatile UrlMatcher MATCHER;

    public static void triggerPreload(final Context context) {
        // Only trigger loading if MATCHER is null. (If it's null, MATCHER could already be loading,
        // but we don't have any way of being certain - and there's no real harm since we're not
        // blocking anything else.)
        if (MATCHER == null) {
            new AsyncTask<Void, Void, Void>() {
                @Override
                protected Void doInBackground(Void... voids) {
                    // We don't need the result here - we just want to trigger loading
                    getMatcher(context);
                    return null;
                }
            }.execute();
        }
    }

    @WorkerThread private static synchronized UrlMatcher getMatcher(final Context context) {
        if (MATCHER == null) {
            MATCHER = UrlMatcher.loadMatcher(context, R.raw.blocklist, new int[] { R.raw.google_mapping }, R.raw.entitylist);
        }
        return MATCHER;
    }

    /**
     * true if blocking is enabled, false otherwise.
     *
     * We cache the SharedPreferences value in a field here to pre-emptively improve performance,
     * rather than calling into SharedPreferences directly, or accessing a global getter (which
     * delegates to shared prefs). The value is updated via a shared preference change listener.
     */
    private boolean blockingEnabled;
    /* package */ String currentPageURL;

    /* package */ TrackingProtectionWebViewClient(final Context context) {
        // Hopefully we have loaded background data already. We call triggerPreload() to try to trigger
        // background loading of the lists as early as possible.
        triggerPreload(context);

        final Settings settings = Settings.getInstance(context);
        settings.getPreferences().registerOnSharedPreferenceChangeListener(this);
        this.blockingEnabled = settings.isBlockingEnabled();

    }

    public void setBlockingEnabled(boolean enabled) {
        // We manage this globally with Shared Prefs now.
        //this.blockingEnabled = enabled;
    }

    public boolean isBlockingEnabled() {
        return blockingEnabled;
    }

    // TODO we need to figure out what to do with the shouldInterceptRequest method that has a request
    // String instead of WebResourceRequest
    // WebResourceRequest was added in API21 and there is no equivalent AmazonWebResourceRequest
    @Override
    public AmazonWebResourceResponse shouldInterceptRequest(final AmazonWebView view, final String request) {
        if (!blockingEnabled) {
            return super.shouldInterceptRequest(view, request);
        }

        final Uri resourceUri = Uri.parse(request);

        // shouldInterceptRequest() might be called _before_ onPageStarted or shouldOverrideUrlLoading
        // are called (this happens when the webview is first shown). However we are notified of the URL
        // via notifyCurrentURL in that case.
        final String scheme = resourceUri.getScheme();

//        if (!request.isForMainFrame() &&
//                !scheme.equals("http") && !scheme.equals("https")) {
//            // Block any malformed non-http(s) URIs. WebView will already ignore things like market: URLs,
//            // but not in all cases (malformed market: URIs, such as market:://... will still end up here).
//            // (Note: data: URIs are automatically handled by WebView, and won't end up here either.)
//            // file:// URIs are disabled separately by setting WebSettings.setAllowFileAccess()
//            return new AmazonWebResourceResponse(null, null, null);
//        }

        // WebView always requests a favicon, even though it won't be used anywhere. This check
        // isn't able to block all favicons (some of them will be loaded using <link rel="shortcut icon">
        // with a custom URL which we can't match or detect), but reduces the amount of unnecessary
        // favicon loading that's performed.
        final String path = resourceUri.getPath();
        if (path != null && path.endsWith("/favicon.ico")) {
            return new AmazonWebResourceResponse(null, null, null);
        }

        final UrlMatcher blockedSiteMatcher = getMatcher(view.getContext());

        // Don't block the main frame from being loaded. This also protects against cases where we
        // open a link that redirects to another app (e.g. to the play store).
        final Uri currentPageUri = Uri.parse(currentPageURL);

        // Matches is true if the resourceUri is on the blocklist and is not a first party request.
        // The matcher code could be better named to make this apparent.
        if (blockedSiteMatcher.matches(resourceUri, currentPageUri)) {
            return new AmazonWebResourceResponse(null, null, null);
        }

        return super.shouldInterceptRequest(view, request);
    }

    /**
     * Notify that the user has requested a new URL. This MUST be called before loading a new URL
     * into the webview: sometimes content requests might begin before the WebView itself notifies
     * the WebViewClient via onpageStarted/shouldOverrideUrlLoading. If we don't know the current page
     * URL then the entitylist whitelists might not work if we're trying to load an explicitly whitelisted
     * page.
     */
    public void notifyCurrentURL(final String url) {
        currentPageURL = url;
    }

    @Override
    public void onPageStarted(AmazonWebView view, String url, Bitmap favicon) {
        currentPageURL = url;

        super.onPageStarted(view, url, favicon);
    }

    @Override
    public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences, final String key) {
        if (IWebView.TRACKING_PROTECTION_ENABLED_PREF.equals(key)) {
            blockingEnabled = sharedPreferences.getBoolean(IWebView.TRACKING_PROTECTION_ENABLED_PREF, IWebView.TRACKING_PROTECTION_ENABLED_DEFAULT);
        }
    }
}
