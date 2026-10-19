package io.stridehub.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.JsResult;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.Toast;

import org.json.JSONObject;

/** The native app owns connection/session lifecycle; server pages own all health and training data. */
public final class MainActivity extends Activity {
    private enum Stage { CONNECT, LOGIN, SUBMITTED, RESTORE, READY }
    private FrameLayout frame;
    private SharedPreferences preferences;
    private ServerAddress server;
    private String username = "";
    private String pendingPassword;
    private boolean remember = true;
    private boolean allowHttp;
    private Stage stage = Stage.CONNECT;
    private BrowserLayout browser;
    private FileTransfers transfers;
    private int generation;
    private boolean failedPage;
    private String retryUrl;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("connection", MODE_PRIVATE);
        frame = new FrameLayout(this);
        frame.setBackgroundColor(ConnectionScreen.PAPER);
        frame.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        setContentView(frame);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
        transfers = new FileTransfers(this);
        username = preferences.getString("username", "");
        allowHttp = preferences.getBoolean("http", false);
        remember = preferences.getBoolean("remember", true);
        String address = preferences.getString("server", "");
        if (!address.isEmpty() && remember) {
            try {
                server = ServerAddress.parse(address, allowHttp);
                stage = Stage.RESTORE;
                openBrowser();
                browser.web.loadUrl(server.route("/admin/training/today"));
                return;
            } catch (IllegalArgumentException ignored) { /* Return to editable connection form. */ }
        }
        showConnection(null);
    }

    private void showConnection(String message) {
        pendingPassword = null;
        stage = Stage.CONNECT;
        generation++;
        destroyBrowser();
        frame.removeAllViews();
        frame.addView(ConnectionScreen.create(this, server == null ? preferences.getString("server", "") : server.baseUrl(),
                username, allowHttp, remember, message, this::connect));
    }

    private void connect(String address, String user, String password, boolean http, boolean stay) {
        server = ServerAddress.parse(address, http);
        username = user;
        remember = stay;
        allowHttp = http;
        pendingPassword = password;
        stage = Stage.LOGIN;
        generation++;
        int current = generation;
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        keyboard.hideSoftInputFromWindow(frame.getWindowToken(), 0);
        destroyBrowser();
        openBrowser();
        clearWebData(() -> {
            if (current == generation && browser != null) browser.web.loadUrl(server.route("/admin/login"));
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void openBrowser() {
        browser = new BrowserLayout(this, server, new BrowserLayout.Actions() {
            @Override public void navigate(String path) {
                if (stage == Stage.READY) browser.web.loadUrl(server.route(path));
            }
            @Override public void menu(View anchor) { showMenu(anchor); }
            @Override public void retry() {
                if (browser != null) browser.web.loadUrl(retryUrl == null ? server.route("/admin/training/today") : retryUrl);
            }
        });
        browser.tabs.setVisibility(stage == Stage.READY ? View.VISIBLE : View.GONE);
        frame.removeAllViews();
        frame.addView(browser.root);
        WebView web = browser.web;
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setUserAgentString(settings.getUserAgentString() + " StrideHub/0.1.0");
        web.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        web.setDownloadListener((url, agent, disposition, mime, length) -> {
            if (stage == Stage.READY) transfers.download(server, url, agent, disposition, mime);
        });
    }

    private final class Client extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (browser == null || view != browser.web) return true;
            String url = request.getUrl().toString();
            if (!request.isForMainFrame()) return !server.isTrustedUrl(url);
            if (server.isTrustedUrl(url)) return false;
            if (request.hasGesture()) external(url);
            else fail("服务器跳转到了其他地址，请检查服务 URL。");
            return true;
        }

        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            String scheme = request.getUrl().getScheme();
            if ("http".equals(scheme) && (!server.isHttp() || !server.isTrustedUrl(request.getUrl().toString()))) {
                return new WebResourceResponse("text/plain", "utf-8", 403, "Blocked", java.util.Collections.emptyMap(), new java.io.ByteArrayInputStream(new byte[0]));
            }
            return null;
        }

        @Override public void onPageStarted(WebView view, String url, Bitmap icon) {
            if (browser == null || view != browser.web) return;
            if (!server.isTrustedUrl(url)) {
                view.stopLoading();
                fail("已阻止离开配置的服务器。");
                return;
            }
            failedPage = false;
            retryUrl = url;
            browser.notice.setVisibility(View.GONE);
            browser.progress.setVisibility(View.VISIBLE);
        }

        @Override public void onPageFinished(WebView view, String url) {
            if (browser == null || view != browser.web || failedPage || !server.isTrustedUrl(url)) return;
            browser.progress.setVisibility(View.INVISIBLE);
            CookieManager.getInstance().flush();
            if (server.isLoginUrl(url)) {
                if (stage == Stage.LOGIN && pendingPassword != null) {
                    String script = LoginScript.build(server, username, pendingPassword, remember);
                    pendingPassword = null;
                    stage = Stage.SUBMITTED;
                    int current = generation;
                    view.evaluateJavascript(script, result -> {
                        if (current != generation || stage != Stage.SUBMITTED) return;
                        try {
                            if (!"submitted".equals(new JSONObject(result).optString("status"))) showConnection("无法识别服务器登录表单，请确认这是 StrideHub 服务地址。");
                        } catch (Exception ignored) { showConnection("无法完成登录，请检查服务地址后重试。"); }
                    });
                } else {
                    showConnection(stage == Stage.SUBMITTED ? "登录未成功，请检查用户名和密码后重试。" : "登录已过期，请重新输入密码。");
                }
                return;
            }
            if (stage == Stage.SUBMITTED || stage == Stage.RESTORE || stage == Stage.LOGIN) {
                if (!server.isRouteUrl(url, "/admin/training/today")) {
                    view.loadUrl(server.route("/admin/training/today"));
                    return;
                }
                stage = Stage.READY;
                pendingPassword = null;
                preferences.edit().putString("server", server.baseUrl()).putString("username", username)
                        .putBoolean("http", allowHttp).putBoolean("remember", remember).apply();
                view.clearHistory();
                browser.tabs.setVisibility(View.VISIBLE);
            }
        }

        @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (browser == null || view != browser.web) return;
            if (request.isForMainFrame()) fail("连接失败，请检查服务是否启动、手机网络和 URL。");
        }

        @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
            if (browser == null || view != browser.web) return;
            if (request.isForMainFrame()) fail("服务器返回 HTTP " + response.getStatusCode() + "，请检查地址或稍后重试。");
        }

        @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.cancel();
            if (browser == null || view != browser.web) return;
            fail("HTTPS 证书无效，连接已停止。请修复服务器证书后重试。");
        }

        @Override public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
            if (browser == null || view != browser.web) return true;
            showConnection("系统释放了页面，请重新连接。已保存的数据仍在服务器上。");
            return true;
        }
    }

    private final class Chrome extends WebChromeClient {
        @Override public void onProgressChanged(WebView view, int progress) {
            if (browser != null && view == browser.web) browser.progress.setProgress(progress);
        }
        @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (browser == null || view != browser.web || stage != Stage.READY || !server.isTrustedUrl(view.getUrl())) {
                callback.onReceiveValue(null);
                return true;
            }
            return transfers.choose(callback, params);
        }
        @Override public boolean onJsBeforeUnload(WebView view, String url, String message, JsResult result) {
            if (browser == null || view != browser.web) { result.cancel(); return true; }
            new AlertDialog.Builder(MainActivity.this).setTitle("离开当前页面？")
                    .setMessage("当前有未保存内容，离开后可能丢失。")
                    .setPositiveButton("离开", (dialog, which) -> result.confirm())
                    .setNegativeButton("继续编辑", (dialog, which) -> result.cancel())
                    .setOnCancelListener(dialog -> result.cancel()).show();
            return true;
        }
        @Override public boolean onCreateWindow(WebView view, boolean dialog, boolean gesture, android.os.Message result) {
            if (browser == null || view != browser.web) return false;
            if (!gesture) return false;
            WebView.HitTestResult hit = view.getHitTestResult();
            String url = hit == null ? null : hit.getExtra();
            if (url != null && server.isTrustedUrl(url)) view.loadUrl(url);
            else if (url != null) external(url);
            return false;
        }
    }

    private void fail(String message) {
        pendingPassword = null;
        failedPage = true;
        if (stage != Stage.READY) showConnection(message);
        else if (browser != null) browser.error(message);
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        if (stage == Stage.READY) {
            menu.getMenu().add("刷新页面").setOnMenuItemClickListener(item -> { browser.web.reload(); return true; });
            menu.getMenu().add("运动记录管理").setOnMenuItemClickListener(item -> { browser.web.loadUrl(server.route("/admin/activities")); return true; });
        }
        menu.getMenu().add("切换服务器 / 账号").setOnMenuItemClickListener(item -> { leave(false); return true; });
        menu.getMenu().add("退出登录").setOnMenuItemClickListener(item -> { leave(true); return true; });
        menu.show();
    }

    private void leave(boolean logout) {
        new AlertDialog.Builder(this).setTitle(logout ? "退出本机登录？" : "切换连接？")
                .setMessage("请先保存当前页面内容。本机登录状态将被清除，服务器上的数据会保留。")
                .setNegativeButton("取消", null).setPositiveButton("继续", (dialog, which) -> {
                    pendingPassword = null;
                    generation++;
                    destroyBrowser();
                    clearWebData(() -> showConnection(logout ? "已退出本机登录。" : null));
                }).show();
    }

    private void clearWebData(Runnable complete) {
        if (browser != null) { browser.web.clearCache(true); browser.web.clearHistory(); }
        WebStorage.getInstance().deleteAllData();
        CookieManager.getInstance().removeAllCookies(value -> { CookieManager.getInstance().flush(); complete.run(); });
    }

    private void external(String value) {
        Uri uri = Uri.parse(value);
        if ((!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null) {
            Toast.makeText(this, "不支持打开这个链接。", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("在浏览器中打开外部链接？").setMessage(uri.getHost())
                .setNegativeButton("取消", null).setPositiveButton("打开", (dialog, which) -> {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)); }
                    catch (android.content.ActivityNotFoundException ignored) { Toast.makeText(this, "没有可用的浏览器。", Toast.LENGTH_SHORT).show(); }
                }).show();
    }

    @SuppressLint("GestureBackNavigation") // API 33+ uses OnBackInvokedDispatcher; this covers Android 8–12.
    @Override public void onBackPressed() { handleBack(); }

    private void handleBack() {
        if (browser != null && stage == Stage.READY && browser.web.canGoBack()) browser.web.goBack();
        else if (stage == Stage.LOGIN || stage == Stage.SUBMITTED || stage == Stage.RESTORE) showConnection(null);
        else moveTaskToBack(true);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        if (!transfers.result(request, result, data)) super.onActivityResult(request, result, data);
    }

    @Override protected void onPause() {
        CookieManager.getInstance().flush();
        super.onPause();
    }

    private void destroyBrowser() {
        if (browser != null) {
            browser.web.stopLoading();
            browser.web.clearCache(true);
            browser.web.clearHistory();
            browser.root.removeView(browser.web);
            browser.web.destroy();
            browser = null;
        }
    }

    @Override protected void onDestroy() {
        pendingPassword = null;
        generation++;
        transfers.close();
        destroyBrowser();
        super.onDestroy();
    }
}
