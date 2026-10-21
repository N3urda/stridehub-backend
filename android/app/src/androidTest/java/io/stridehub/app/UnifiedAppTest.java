package io.stridehub.app;
import android.content.Context;
import android.content.Intent;
import android.test.ActivityInstrumentationTestCase2;
import android.app.Instrumentation;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import io.stridehub.app.scale.WeighingActivity;
@SuppressWarnings("deprecation")
public final class UnifiedAppTest extends ActivityInstrumentationTestCase2<MainActivity> {
    public UnifiedAppTest(){super(MainActivity.class);}
    public void testOfflineWeighingEntryAndReturn() throws Throwable {
        getInstrumentation().getTargetContext().getSharedPreferences("connection",Context.MODE_PRIVATE).edit().clear().commit();
        MainActivity main=getActivity();
        Instrumentation.ActivityMonitor monitor=getInstrumentation().addMonitor(WeighingActivity.class.getName(),null,false);
        try {
            runTestOnUiThread(()->{
                View entry=find(main.getWindow().getDecorView(),"离线称重与本机记录");assertNotNull(entry);entry.performClick();
            });
            WeighingActivity weighing=(WeighingActivity)monitor.waitForActivityWithTimeout(5000);
            assertNotNull("Offline weighing must not require NAS login",weighing);
            getInstrumentation().runOnMainSync(()->{
                assertNotNull(find(weighing.getWindow().getDecorView(),"身体记录"));
                assertNotNull(find(weighing.getWindow().getDecorView(),"称重"));
                find(weighing.getWindow().getDecorView(),"返回 StrideHub").performClick();
            });
            getInstrumentation().waitForIdleSync();assertTrue(weighing.isFinishing());
        } finally {getInstrumentation().removeMonitor(monitor);}
    }
    private static View find(View view,String text) {
        if(view instanceof TextView && text.contentEquals(((TextView)view).getText()))return view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=find(group.getChildAt(i),text);if(found!=null)return found;}}
        return null;
    }
}
