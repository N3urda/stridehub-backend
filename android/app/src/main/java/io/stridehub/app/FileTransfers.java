package io.stridehub.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Uses user-selected document URIs; never grants a web page unrestricted filesystem access. */
final class FileTransfers {
    private static final int PICK_FILE = 210;
    private static final int SAVE_FILE = 211;
    private final Activity activity;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private ValueCallback<Uri[]> upload;
    private ServerAddress downloadServer;
    private String downloadUrl;
    private String downloadCookie;
    private String downloadAgent;

    FileTransfers(Activity activity) {
        this.activity = activity;
    }

    boolean choose(ValueCallback<Uri[]> callback, WebChromeClient.FileChooserParams params) {
        if (upload != null) {
            callback.onReceiveValue(null);
            return true;
        }
        upload = callback;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // FIT/GPX/TCX archives often have no registered MIME type on Android.
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivityForResult(intent, PICK_FILE);
        } catch (android.content.ActivityNotFoundException error) {
            upload.onReceiveValue(null);
            upload = null;
            toast("没有可用的系统文件选择器。");
        }
        return true;
    }

    void download(ServerAddress server, String url, String agent, String disposition, String mime) {
        if (downloadUrl != null) {
            toast("请先完成当前文件的保存位置选择。");
            return;
        }
        if (!server.isTrustedUrl(url) || server.isLoginUrl(url)) {
            toast("无法从当前服务器以外的地址下载。");
            return;
        }
        downloadServer = server;
        downloadUrl = url;
        downloadAgent = agent;
        downloadCookie = CookieManager.getInstance().getCookie(url);
        String file = URLUtil.guessFileName(url, disposition, mime).replaceAll("[\\\\/\\p{Cntrl}]", "_");
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mime != null && mime.matches("[\\w.+-]+/[\\w.+-]+") ? mime : "application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, file);
        try {
            activity.startActivityForResult(intent, SAVE_FILE);
        } catch (android.content.ActivityNotFoundException error) {
            clearDownload();
            toast("没有可用的系统保存位置选择器。");
        }
    }

    boolean result(int request, int code, Intent data) {
        if (request == PICK_FILE) {
            if (upload == null) return true;
            ArrayList<Uri> selected = new ArrayList<>();
            if (code == Activity.RESULT_OK && data != null) {
                ClipData clips = data.getClipData();
                if (clips != null) {
                    for (int i = 0; i < Math.min(clips.getItemCount(), 100); i++) addContentUri(selected, clips.getItemAt(i).getUri());
                } else addContentUri(selected, data.getData());
            }
            upload.onReceiveValue(selected.isEmpty() ? null : selected.toArray(new Uri[0]));
            upload = null;
            return true;
        }
        if (request != SAVE_FILE) return false;
        Uri destination = data == null ? null : data.getData();
        if (code == Activity.RESULT_OK && destination != null && "content".equals(destination.getScheme()) && downloadUrl != null) {
            ServerAddress server = downloadServer;
            String url = downloadUrl;
            String cookie = downloadCookie;
            String agent = downloadAgent;
            toast("正在保存文件…");
            worker.execute(() -> save(server, url, cookie, agent, destination));
        }
        clearDownload();
        return true;
    }

    private void save(ServerAddress server, String url, String cookie, String agent, Uri destination) {
        HttpURLConnection connection = null;
        try {
            for (int hop = 0; hop <= 5; hop++) {
                if (!server.isTrustedUrl(url) || server.isLoginUrl(url)) throw new java.io.IOException("下载已跳转或登录过期，请重新登录。");
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                if (cookie != null) connection.setRequestProperty("Cookie", cookie);
                if (agent != null) connection.setRequestProperty("User-Agent", agent);
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new java.io.IOException("服务器未返回有效文件地址。");
                    url = new URL(new URL(url), location).toString();
                    connection.disconnect();
                    connection = null;
                    continue;
                }
                if (status != 200) throw new java.io.IOException("文件下载失败（HTTP " + status + "）。");
                String contentType = connection.getContentType();
                if (contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).contains("text/html")) {
                    throw new java.io.IOException("服务器返回了网页，请确认登录状态后重试。");
                }
                try (InputStream input = connection.getInputStream(); OutputStream output = activity.getContentResolver().openOutputStream(destination, "wt")) {
                    if (output == null) throw new java.io.IOException("无法写入选择的位置。");
                    byte[] buffer = new byte[32768];
                    int count;
                    long total = 0;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        if (total > 256L * 1024 * 1024 || Thread.currentThread().isInterrupted()) throw new java.io.IOException("下载已停止，文件超过 256 MB 或任务已取消。");
                        output.write(buffer, 0, count);
                    }
                }
                activity.runOnUiThread(() -> toast("文件已保存。"));
                return;
            }
            throw new java.io.IOException("下载跳转次数过多。");
        } catch (Exception error) {
            activity.runOnUiThread(() -> toast("下载未完成，请检查网络、登录和文件位置后重试。"));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void addContentUri(ArrayList<Uri> selected, Uri uri) {
        if (uri != null && "content".equals(uri.getScheme()) && selected.size() < 100) selected.add(uri);
    }

    private void clearDownload() {
        downloadUrl = null;
        downloadServer = null;
        downloadCookie = null;
        downloadAgent = null;
    }

    void close() {
        if (upload != null) upload.onReceiveValue(null);
        upload = null;
        clearDownload();
        worker.shutdownNow();
    }

    private void toast(String message) {
        if (!activity.isDestroyed()) Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }
}
