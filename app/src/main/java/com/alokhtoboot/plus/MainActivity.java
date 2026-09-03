package com.alokhtoboot.plus;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONArray;

public final class MainActivity extends Activity {
    private static final String START_URL = "http://10.10.10.10";
    private static final String DOWNLOAD_REGEX = "(?i)(?:/download|\\.(?:mp4|mkv|avi|mov|webm|ts|m3u8|zip|rar|apk|pdf))(?:[?&#/]|$)";

    private WebView web;
    private ProgressBar progress;
    private EditText loungeUrl;
    private TextView status;
    private Button autoSearch;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        web = findViewById(R.id.web);
        progress = findViewById(R.id.progress);
        loungeUrl = findViewById(R.id.loungeUrl);
        status = findViewById(R.id.status);
        autoSearch = findViewById(R.id.autoSearch);
        loungeUrl.setText(START_URL);
        configureWebView();
        autoSearch.setOnClickListener(v -> autoSearchForLounge());
        web.loadUrl(START_URL);
    }

    private void configureWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.addJavascriptInterface(new DownloadBridge(), "AndroidDownload");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String u = request.getUrl().toString();
                if (isDownload(u)) {
                    enqueueDownload(u, web.getSettings().getUserAgentString(), null);
                    return true;
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                progress.setVisibility(View.VISIBLE);
                setStatus("جاري تحميل الصفحة...");
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                setStatus("تم تحميل الصفحة");
                injectDownloadBridge();
            }
        });
        web.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
                enqueueDownload(url, userAgent, mimeType));
    }

    private void injectDownloadBridge() {
        String js = "(function(){if(window.__octopusDl)return;window.__octopusDl=1;document.addEventListener('click',function(e){var el=e.target&&e.target.closest?e.target.closest('a,button,[data-url],[data-download],[onclick]'):null;if(!el)return;var u=el.href||el.getAttribute('data-download')||el.getAttribute('data-url')||el.getAttribute('href');var t=(el.innerText||el.textContent||'');if(u&&(el.hasAttribute('download')||/download|تحميل|تنزيل/i.test(t)||/(?:download|\\.(?:mp4|mkv|avi|mov|webm|ts|m3u8|zip|rar|apk|pdf))(?:[?&#/]|$)/i.test(String(u)))){e.preventDefault();e.stopPropagation();window.AndroidDownload.download(String(u));}},true);})();";
        web.evaluateJavascript(js, null);
    }

    private final class DownloadBridge {
        @android.webkit.JavascriptInterface
        public void download(String url) {
            runOnUiThread(() -> enqueueDownload(url, web.getSettings().getUserAgentString(), null));
        }
    }

    private boolean isDownload(String u) {
        return u != null && u.matches(".*" + DOWNLOAD_REGEX + ".*");
    }

    private void autoSearchForLounge() {
        final String raw = loungeUrl.getText().toString().trim();
        if (raw.isEmpty()) {
            setStatus("أدخل رابط الاستراحة أولاً");
            return;
        }
        final String url = normalizeUrl(raw);
        loungeUrl.setText(url);
        autoSearch.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        setStatus("جاري البحث عن الاستراحة...");
        web.loadUrl(url);
        web.postDelayed(() -> inspectPageForLounge(0), 1400);
    }

    private void inspectPageForLounge(int attempt) {
        if (attempt > 8) {
            autoSearch.setEnabled(true);
            progress.setVisibility(View.GONE);
            setStatus("لم يتم العثور على الاستراحة — يمكنك إدخال رابط مباشر وتجربته");
            return;
        }
        String js = "(function(){var out=[];var els=document.querySelectorAll('a[href],button,[data-url],[data-download]');" +
                "els.forEach(function(e){var u=e.href||e.getAttribute('data-url')||e.getAttribute('data-download')||'';var t=(e.innerText||e.textContent||'').trim();" +
                "if(u && (/استراحة\\s*الأخطبوط/i.test(t)||/استراحة\\s*الأخطبوط/i.test(e.getAttribute('aria-label')||'')||/download|تحميل|تنزيل/i.test(t)||/(?:download|\\.(?:mp4|mkv|avi|mov|webm|ts|m3u8|zip|rar|apk|pdf))(?:[?&#/]|$)/i.test(String(u)))) out.push(String(u));});" +
                "return JSON.stringify(out);})()";
        web.evaluateJavascript(js, value -> {
            String found = firstUsefulLink(unquoteJsString(value == null ? "" : value));
            if (found != null) {
                runOnUiThread(() -> {
                    autoSearch.setEnabled(true);
                    progress.setVisibility(View.GONE);
                    loungeUrl.setText(found);
                    setStatus("تم العثور على الاستراحة");
                    web.loadUrl(found);
                });
            } else {
                web.postDelayed(() -> inspectPageForLounge(attempt + 1), 700);
            }
        });
    }

    private String firstUsefulLink(String jsonArray) {
        try {
            JSONArray a = new JSONArray(jsonArray);
            String fallback = null;
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < a.length(); i++) {
                String c = a.optString(i, "").replace("\\/", "/").trim();
                if (c.isEmpty() || !seen.add(c)) continue;
                if (c.matches("(?i)^https?://.*")) {
                    if (c.matches("(?i).*استراحة.*الأخطبوط.*|.*" + DOWNLOAD_REGEX + ".*")) return c;
                    if (fallback == null) fallback = c;
                }
            }
            return fallback;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String unquoteJsString(String value) {
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') value = value.substring(1, value.length() - 1);
        return value.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\u0026", "&").replace("\\/", "/").replace("\\n", "\n").replace("\\r", "\r");
    }

    private String normalizeUrl(String raw) {
        return raw.matches("(?i)^https?://.*") ? raw : "http://" + raw;
    }

    private File sdTarget() {
        File[] dirs = getExternalFilesDirs(null);
        if (dirs != null) {
            String primary = Environment.getExternalStorageDirectory().getAbsolutePath();
            for (File d : dirs) {
                if (d == null) continue;
                String p = d.getAbsolutePath();
                if (p.startsWith("/storage/") && !p.startsWith(primary)) {
                    File packageRoot = d.getParentFile();
                    if (packageRoot != null) return new File(packageRoot, "downloads");
                }
            }
        }
        File d = getExternalFilesDir(null);
        return new File(d, "downloads");
    }

    private void enqueueDownload(String url, String userAgent, String mime) {
        try {
            if (url == null || url.trim().isEmpty()) throw new IOException("رابط التنزيل فارغ");
            File dir = sdTarget();
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("تعذر تجهيز مجلد التنزيل");
            String name = android.webkit.URLUtil.guessFileName(url, null, mime);
            if (name == null || name.trim().isEmpty()) name = "download.bin";
            name = uniqueName(dir, name);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            if (userAgent != null) req.addRequestHeader("User-Agent", userAgent);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) req.addRequestHeader("Cookie", cookie);
            if (mime != null && !mime.isEmpty()) req.setMimeType(mime);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setTitle(name);
            req.setDescription("تنزيل الأخطبوط بلس");
            req.setDestinationUri(Uri.fromFile(new File(dir, name)));
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) throw new IOException("مدير التنزيل غير متاح");
            dm.enqueue(req);
            Toast.makeText(this, "بدأ تنزيل: " + name, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "فشل التنزيل: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String uniqueName(File dir, String requested) {
        String base = requested, ext = "";
        int dot = requested.lastIndexOf('.');
        if (dot > 0) { base = requested.substring(0, dot); ext = requested.substring(dot); }
        File f = new File(dir, requested);
        int i = 1;
        while (f.exists()) f = new File(dir, base + " (" + i++ + ")" + ext);
        return f.getName();
    }

    private void setStatus(String text) { if (status != null) status.setText(text); }

    @Override public void onBackPressed() { if (web.canGoBack()) web.goBack(); else super.onBackPressed(); }
    @Override protected void onDestroy() { executor.shutdownNow(); if (web != null) web.destroy(); super.onDestroy(); }
}
