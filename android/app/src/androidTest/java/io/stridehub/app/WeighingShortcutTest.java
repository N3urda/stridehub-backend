package io.stridehub.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.test.ActivityInstrumentationTestCase2;
import io.stridehub.app.scale.WeighingActivity;
import java.lang.reflect.Field;

/** The launcher action must reach local weighing without restoring a NAS webpage behind it. */
@SuppressWarnings("deprecation")
public final class WeighingShortcutTest extends ActivityInstrumentationTestCase2<MainActivity> {
    public WeighingShortcutTest(){super(MainActivity.class);}

    public void testShortcutDefersWebSessionUntilReturningToMain() throws Throwable {
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
            runTestOnUiThread(()->{
                assertNull("No WebView may restore an expired login in the background",read(main,"browser"));
                assertEquals(Boolean.TRUE,read(main,"awaitingWeighing"));
            });
        } finally {
            getInstrumentation().getTargetContext().getSharedPreferences("connection",0).edit().clear().commit();
            if(weighing!=null){Activity local=weighing;getInstrumentation().runOnMainSync(local::finish);}
            getInstrumentation().removeMonitor(monitor);
            getInstrumentation().waitForIdleSync();
        }
    }

    private static Object read(Object target,String name){try{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}catch(Exception error){throw new AssertionError(error);}}
}
