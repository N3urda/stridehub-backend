package io.stridehub.app;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.test.ActivityInstrumentationTestCase2;
import io.stridehub.app.scale.WeighingActivity;

/** Launcher action works even with legacy connection preferences and no native credential. */
@SuppressWarnings("deprecation")
public final class WeighingShortcutTest extends ActivityInstrumentationTestCase2<MainActivity> {
    public WeighingShortcutTest(){super(MainActivity.class);}
    public void testShortcutOpensNativeWeighingWithoutLogin() throws Throwable {
        NativeSession.clear(getInstrumentation().getTargetContext());
        getInstrumentation().getTargetContext().getSharedPreferences("connection",0).edit().clear()
            .putString("server","https://shortcut.invalid").putBoolean("remember",true).commit();
        setActivityIntent(new Intent().setAction(WeighingShortcut.ACTION));
        Instrumentation.ActivityMonitor monitor=getInstrumentation().addMonitor(WeighingActivity.class.getName(),null,false);
        Activity weighing=null;
        try {
            MainActivity main=getActivity();
            weighing=monitor.waitForActivityWithTimeout(5000);
            assertNotNull("Shortcut opens native weighing",weighing);
            getInstrumentation().waitForIdleSync();
            runTestOnUiThread(()->assertFalse(NativeUiTestSupport.containsWebSurface(main.getWindow().getDecorView())));
            assertFalse(NativeSession.signedIn(main));
        } finally {
            getInstrumentation().getTargetContext().getSharedPreferences("connection",0).edit().clear().commit();
            if(weighing!=null){Activity local=weighing;getInstrumentation().runOnMainSync(local::finish);}
            getInstrumentation().removeMonitor(monitor);
            getInstrumentation().waitForIdleSync();
        }
    }
}
