package io.stridehub.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.test.ActivityInstrumentationTestCase2;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

/** Repeated taps must not redirect a pending document selection to another request. */
@SuppressWarnings("deprecation")
public final class FileTransferLifecycleTest extends ActivityInstrumentationTestCase2<MainActivity> {
    public FileTransferLifecycleTest() { super(MainActivity.class); }

    @Override protected void setUp() throws Exception {
        super.setUp();
        getInstrumentation().getTargetContext().getSharedPreferences("connection", Context.MODE_PRIVATE)
            .edit().clear().commit();
        setActivityInitialTouchMode(true);
    }

    public void testSecondDownloadKeepsFirstPickerRequest() throws Throwable {
        MainActivity activity = getActivity();
        Instrumentation.ActivityMonitor monitor = blockPicker(Intent.ACTION_CREATE_DOCUMENT);
        try {
            runTestOnUiThread(() -> {
                FileTransfers transfers = new FileTransfers(activity);
                String firstUrl = "https://picker.invalid/first.fit";
                try {
                    write(transfers, "downloadUrl", firstUrl);
                    transfers.download(ServerAddress.parse("https://picker.invalid", false),
                        "https://picker.invalid/second.fit", "test", "attachment; filename=second.fit", "application/octet-stream");
                    assertEquals(firstUrl, read(transfers, "downloadUrl"));
                    assertEquals("A second picker must not open while the first is pending", 0, monitor.getHits());
                } finally { transfers.close(); }
            });
        } finally { getInstrumentation().removeMonitor(monitor); }
    }

    public void testSecondUploadDoesNotCancelOrReplaceFirstCallback() throws Throwable {
        MainActivity activity = getActivity();
        Instrumentation.ActivityMonitor monitor = blockPicker(Intent.ACTION_OPEN_DOCUMENT);
        try {
            runTestOnUiThread(() -> {
                FileTransfers transfers = new FileTransfers(activity);
                AtomicInteger firstCalls = new AtomicInteger();
                AtomicInteger secondCalls = new AtomicInteger();
                ValueCallback<Uri[]> first = values -> firstCalls.incrementAndGet();
                ValueCallback<Uri[]> second = values -> {
                    assertNull("The rejected second picker must return cancellation", values);
                    secondCalls.incrementAndGet();
                };
                try {
                    write(transfers, "upload", first);
                    assertTrue(transfers.choose(second, chooserParams()));
                    assertEquals(0, firstCalls.get());
                    assertEquals(1, secondCalls.get());
                    assertSame(first, read(transfers, "upload"));
                    assertEquals("A second picker must not open while the first is pending", 0, monitor.getHits());
                } finally { transfers.close(); }
            });
        } finally { getInstrumentation().removeMonitor(monitor); }
    }

    private Instrumentation.ActivityMonitor blockPicker(String action) {
        IntentFilter filter = new IntentFilter(action);
        filter.addCategory(Intent.CATEGORY_OPENABLE);
        try { filter.addDataType("*/*"); }
        catch (IntentFilter.MalformedMimeTypeException error) { throw new AssertionError(error); }
        return getInstrumentation().addMonitor(filter,
            new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true);
    }

    private static WebChromeClient.FileChooserParams chooserParams() {
        return new WebChromeClient.FileChooserParams() {
            @Override public Intent createIntent() { return new Intent(Intent.ACTION_OPEN_DOCUMENT); }
            @Override public String[] getAcceptTypes() { return new String[]{"*/*"}; }
            @Override public String getFilenameHint() { return null; }
            @Override public int getMode() { return MODE_OPEN; }
            @Override public CharSequence getTitle() { return "Test picker"; }
            @Override public boolean isCaptureEnabled() { return false; }
        };
    }

    private static Object read(FileTransfers transfers, String name) {
        try {
            Field field = FileTransfers.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(transfers);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void write(FileTransfers transfers, String name, Object value) {
        try {
            Field field = FileTransfers.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(transfers, value);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
}
