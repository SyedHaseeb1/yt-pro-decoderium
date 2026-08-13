package com.google.android.youtube.pro.webview;

import android.content.Intent;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.youtube.pro.ForegroundService;
import com.google.android.youtube.pro.MainActivity;

import android.util.Log;

public class YTProWebViewClient extends WebViewClient {
	
	private final MainActivity activity;
	private final YTProWebView web;
	
	public YTProWebViewClient(MainActivity activity, YTProWebView web) {
		this.activity = activity;
		this.web = web;
	}
	
	@Override
	public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
		super.onPageStarted(view, url, favicon);
		Log.d("YTPRO_WVC", "Page started: " + url);
	}

	@Override
	public void onPageFinished(WebView view, String url) {
		Log.d("YTPRO_WVC", "Page finished: " + url);


		// First, create Trusted Types policy to bypass CSP
		String trustedTypesPolicy = "if(window.trustedTypes && window.trustedTypes.createPolicy && !window.trustedTypes.defaultPolicy) {" +
			"window.trustedTypes.createPolicy('default', {" +
			"createHTML: (string) => string," +
			"createScriptURL: (string) => string," +
			"createScript: (string) => string," +
			"createURL: (string) => string" +
			"});" +
			"}";

		web.evaluateJavascript(trustedTypesPolicy, null);

		// Create loader
		String createLoader = "if(!document.getElementById('ytproLoader')) { " +
			"var loader = document.createElement('div'); " +
			"loader.id = 'ytproLoader'; " +
			"loader.style.cssText = 'position:fixed;top:0;left:0;width:100%;height:3px;background:#3ea6ff;z-index:999999;display:none;'; " +
			"document.body.appendChild(loader); " +
			"}";
		web.evaluateJavascript(createLoader, null);

		// Detect navigation changes
		String detectNavigation = "var ytproLastUrl = window.location.href; " +
			"setInterval(() => { " +
			"if(window.location.href !== ytproLastUrl) { " +
			"ytproLastUrl = window.location.href; " +
			"var loader = document.getElementById('ytproLoader'); " +
			"if(loader) loader.style.display = 'block'; " +
			"setTimeout(() => { if(loader) loader.style.display = 'none'; }, 2000); " +
			"} " +
			"}, 100);";
		web.evaluateJavascript(detectNavigation, null);


		// Load Network Logger FIRST (logs all network requests)
		try {
			String networkLoggerContent = readAssetFile("scripts/network-logger.js");
			String networkLoggerScript = "try { " + networkLoggerContent + " } catch(e) { console.error('Network Logger error:', e); }";
			web.evaluateJavascript(networkLoggerScript, null);
			Log.d("YTPRO_WVC", "Network Logger script injected");
		} catch(Exception e) {
			Log.e("YTPRO_WVC", "Failed to load Network Logger script", e);
		}

		// Load Stream Extractor (accesses YouTube player directly)
		try {
			String streamExtractorContent = readAssetFile("scripts/stream-extractor.js");
			String streamExtractorScript = "try { " + streamExtractorContent + " } catch(e) { console.error('Stream Extractor error:', e); }";
			web.evaluateJavascript(streamExtractorScript, null);
			Log.d("YTPRO_WVC", "Stream Extractor script injected inline");
		} catch(Exception e) {
			Log.e("YTPRO_WVC", "Failed to load Stream Extractor script", e);
		}

		// Load Download script (required for download functionality)
		try {
			String downloadContent = readAssetFile("scripts/download.js");
			String downloadScript = "try { " + downloadContent + " } catch(e) { console.error('Download script error:', e); }";
			web.evaluateJavascript(downloadScript, null);
			Log.d("YTPRO_WVC", "Download script injected inline");
		} catch(Exception e) {
			Log.e("YTPRO_WVC", "Failed to load Download script", e);
		}

		// InnerTube script disabled - Network Inspector captures streams directly
		// and innertube.js causes CSP violations due to CDN imports
		Log.d("YTPRO_WVC", "InnerTube script skipped (using Network Inspector instead)");

		// Load YTPro script inline (to bypass Trusted Types CSP)
		try {
			String scriptContent = readAssetFile("scripts/ytpro.js");
			String inlineScript = "try { " + scriptContent + " } catch(e) { console.error('YTPro error:', e); }";
			web.evaluateJavascript(inlineScript, null);
			Log.d("YTPRO_WVC", "YTPro script injected inline");
		} catch(Exception e) {
			Log.e("YTPRO_WVC", "Failed to load YTPro script", e);
		}


		if (!url.contains("youtube.com/watch") && !url.contains("youtube.com/shorts") && activity.isPlaying) {
			activity.isPlaying = false;
			activity.mediaSession = false;
			activity.stopService(new Intent(activity.getApplicationContext(), ForegroundService.class));
		}
		super.onPageFinished(view, url);
	}

	private String readAssetFile(String filename) throws Exception {
		java.io.InputStream is = activity.getAssets().open(filename);
		java.util.Scanner scanner = new java.util.Scanner(is).useDelimiter("\\A");
		return scanner.hasNext() ? scanner.next() : "";
	}
}
