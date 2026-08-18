package com.google.android.youtube.pro.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.google.android.youtube.pro.webview.YTProWebView;

public class MediaCommandReceiver extends BroadcastReceiver {
    private final YTProWebView web;
    private final com.google.android.youtube.pro.MainActivity activity;

    public MediaCommandReceiver(YTProWebView web, com.google.android.youtube.pro.MainActivity activity) {
        this.web = web;
        this.activity = activity;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getExtras() == null) return;
        
        String action = intent.getExtras().getString("actionname");
        Log.e("Action MainActivity", action);

        switch (action) {
            case "PLAY_ACTION":
                web.evaluateJavascript("playVideo();", null);
                break;
            case "PAUSE_ACTION":
                web.evaluateJavascript("pauseVideo();", null);
                break;
            case "NEXT_ACTION":
                web.evaluateJavascript("playNext();", null);
                break;
            case "PREV_ACTION":
                web.evaluateJavascript("playPrev();", null);
                break;
            case "CLOSE_ACTION":
                if (activity != null) {
                    activity.isPlaying = false;
                    activity.mediaSession = false;
                }
                context.stopService(new Intent(context, com.google.android.youtube.pro.ForegroundService.class));
                web.evaluateJavascript("pauseVideo(); window.serviceRunning=false;", null);
                break;
            case "SEEKTO":
                web.evaluateJavascript("seekTo('" + intent.getExtras().getString("pos") + "');", null);
                break;
        }
    }
}
