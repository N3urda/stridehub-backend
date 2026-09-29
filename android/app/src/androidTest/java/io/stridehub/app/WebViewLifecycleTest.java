package io.stridehub.app;

import android.content.Context;
import android.net.Uri;
import android.test.ActivityInstrumentationTestCase2;
import android.view.View;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Native lifecycle regression cases. They intentionally do not claim server authentication coverage. */
@SuppressWarnings("deprecation")
public final class WebViewLifecycleTest extends ActivityInstrumentationTestCase2<MainActivity> {
    public WebViewLifecycleTest() { super(MainActivity.class); }

    @Override protected void setUp() throws Exception {
        super.setUp();
        getInstrumentation().getTargetContext().getSharedPreferences("connection", Context.MODE_PRIVATE)
            .edit().clear().commit();
        setActivityInitialTouchMode(true);
    }

    public void testStaleNetworkErrorDoesNotInvalidateActiveBrowser() throws Throwable {
        MainActivity activity = getActivity();
        runTestOnUiThread(() -> {
            BrowserLayout browser = readyBrowser(activity);
            WebView old = new WebView(activity);
            try {
                // The handler uses frame identity only; no platform-owned error object is needed.
                browser.web.getWebViewClient().onReceivedError(old, request(), null);
                assertActive(activity, browser);
            } finally { old.destroy(); }
        });
    }

    public void testStaleHttpErrorDoesNotInvalidateActiveBrowser() throws Throwable {
        MainActivity activity = getActivity();
        runTestOnUiThread(() -> {
            BrowserLayout browser = readyBrowser(activity);
            WebView old = new WebView(activity);
            try {
                browser.web.getWebViewClient().onReceivedHttpError(old, request(),
                    new WebResourceResponse("text/plain", "utf-8", 503, "Unavailable",
                        Collections.emptyMap(), new ByteArrayInputStream(new byte[0])));
                assertActive(activity, browser);
            } finally { old.destroy(); }
        });
    }

    public void testStaleRendererExitDoesNotDestroyReplacementBrowser() throws Throwable {
        MainActivity activity = getActivity();
        runTestOnUiThread(() -> {
            BrowserLayout browser = readyBrowser(activity);
            WebView old = new WebView(activity);
            try {
                boolean handled = browser.web.getWebViewClient().onRenderProcessGone(old,
                    new RenderProcessGoneDetail() {
                        @Override public boolean didCrash() { return true; }
                        @Override public int rendererPriorityAtExit() { return WebView.RENDERER_PRIORITY_IMPORTANT; }
                    });
                assertTrue(handled);
                assertActive(activity, browser);
            } finally { old.destroy(); }
        });
    }

    public void testCurrentNetworkErrorStillOffersRetry() throws Throwable {
        MainActivity activity = getActivity();
        runTestOnUiThread(() -> {
            BrowserLayout browser = readyBrowser(activity);
            browser.web.getWebViewClient().onReceivedError(browser.web, request(), null);
            assertSame(browser, read(activity, "browser"));
            assertEquals("READY", read(activity, "stage").toString());
            assertEquals(Boolean.TRUE, read(activity, "failedPage"));
            assertEquals(View.VISIBLE, browser.notice.getVisibility());
            assertTrue(browser.status.getText().toString().contains("连接失败"));
        });
    }

    public void testStaleSameOriginFileChooserIsCancelled() throws Throwable {
        MainActivity activity = getActivity();
        runTestOnUiThread(() -> {
            BrowserLayout browser = readyBrowser(activity);
            WebView old = new WebView(activity) {
                @Override public String getUrl() { return "https://lifecycle.invalid/admin/training/today"; }
            };
            AtomicInteger calls = new AtomicInteger();
            try {
                assertTrue(browser.web.getWebChromeClient().onShowFileChooser(old, value -> {
                    assertNull(value);
                    calls.incrementAndGet();
                }, null));
                assertEquals(1, calls.get());
                assertActive(activity, browser);
            } finally { old.destroy(); }
        });
    }

    private BrowserLayout readyBrowser(MainActivity activity) {
        try {
            Field server = MainActivity.class.getDeclaredField("server");
            server.setAccessible(true);
            server.set(activity, ServerAddress.parse("https://lifecycle.invalid", false));
            Field stage = MainActivity.class.getDeclaredField("stage");
            stage.setAccessible(true);
            for (Object value : stage.getType().getEnumConstants()) {
                if ("READY".equals(value.toString())) stage.set(activity, value);
            }
            Method open = MainActivity.class.getDeclaredMethod("openBrowser");
            open.setAccessible(true);
            open.invoke(activity);
            // No loadUrl call: this fixture is only a current native WebView and has no authenticated session.
            return (BrowserLayout) read(activity, "browser");
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Cannot prepare the native lifecycle fixture", error);
        }
    }

    private void assertActive(MainActivity activity, BrowserLayout browser) {
        assertSame(browser, read(activity, "browser"));
        assertEquals("READY", read(activity, "stage").toString());
        assertEquals(Boolean.FALSE, read(activity, "failedPage"));
        assertEquals(View.GONE, browser.notice.getVisibility());
    }

    private static Object read(MainActivity activity, String name) {
        try {
            Field field = MainActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(activity);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Cannot read lifecycle state", error);
        }
    }

    private static WebResourceRequest request() {
        return new WebResourceRequest() {
            @Override public Uri getUrl() { return Uri.parse("https://lifecycle.invalid/admin/training/today"); }
            @Override public boolean isForMainFrame() { return true; }
            @Override public boolean isRedirect() { return false; }
            @Override public boolean hasGesture() { return false; }
            @Override public String getMethod() { return "GET"; }
            @Override public Map<String, String> getRequestHeaders() { return Collections.emptyMap(); }
        };
    }
}
