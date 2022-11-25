package de.vier_bier.habpanelviewer;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.service.dreams.DreamService;

import de.vier_bier.habpanelviewer.openhab.ISseConnectionListener;
import de.vier_bier.habpanelviewer.openhab.SseConnection;

public class WebDreamService extends DreamService {
    private ClientWebView mWebView;
    private NetworkTracker mNetworkTracker;
    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setContentView(R.layout.activity_daydream);
        mNetworkTracker = new NetworkTracker(this);
        mWebView = findViewById(R.id.webView);
        setFullscreen(true);
        setScreenBright(false);
        setInteractive(PreferenceManager.getDefaultSharedPreferences(WebDreamService.this).getBoolean(Constants.PREF_DAYDREAM_INTERACTIVE, false));
    }

    @Override
    public void onDetachedFromWindow(){
        super.onDetachedFromWindow();
        if (mNetworkTracker != null) {
            mNetworkTracker.terminate(this);
            mNetworkTracker = null;
        }

        if (mWebView != null) {
            mWebView.unregister();
        }
    }

    @Override
    public void onDreamingStarted() {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(WebDreamService.this);
        mWebView.initialize(Constants.PREF_DAYDREAM_URL,new ISseConnectionListener() {
            private SseConnection.Status mLastStatus;

            @Override
            public void statusChanged(SseConnection.Status newStatus) {
                //ignore
            }
        }, (url, isHabPanelUrl) -> {
            //ignore
        }, mNetworkTracker);
        mWebView.updateFromPreferences(prefs);
    }
}
