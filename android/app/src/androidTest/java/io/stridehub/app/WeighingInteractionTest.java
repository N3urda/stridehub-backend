package io.stridehub.app;

import android.app.AlertDialog;
import android.os.Bundle;
import android.test.ActivityInstrumentationTestCase2;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import io.stridehub.app.scale.WeighingActivity;
import io.stridehub.app.scale.data.LocalStore;
import io.stridehub.app.scale.core.Measurement;
import io.stridehub.app.sync.NasSync;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Calendar;

/** Exercises the real dialogs and persistence path without Bluetooth or a network server. */
@SuppressWarnings("deprecation")
public final class WeighingInteractionTest extends ActivityInstrumentationTestCase2<WeighingActivity> {
    private long savedId;
    public WeighingInteractionTest(){super(WeighingActivity.class);}

    @Override protected void setUp() throws Exception {
        super.setUp();
        NasSync.disconnect(getInstrumentation().getTargetContext());
        setActivityInitialTouchMode(true);
    }

    @Override protected void tearDown() throws Exception {
        if(savedId>0)try(LocalStore store=new LocalStore(getInstrumentation().getTargetContext())){store.delete(savedId);}
        super.tearDown();
    }

    public void testInvalidManualValuesKeepDraftAndDoNotSave() throws Throwable {
        WeighingActivity activity=getActivity();
        runTestOnUiThread(()->invoke(activity,"showManual"));
        getInstrumentation().waitForIdleSync();
        runTestOnUiThread(()->{
            try(LocalStore store=new LocalStore(activity)){
                int before=store.all().size();
                EditText weight=(EditText)read(activity,"manualWeight"),fat=(EditText)read(activity,"manualFat");
                AlertDialog dialog=(AlertDialog)read(activity,"manualDialog");
                weight.setText("400");fat.setText("22.1");
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                assertTrue("Invalid input must keep the form open",dialog.isShowing());assertNotNull("Weight error must be inline",weight.getError());assertTrue("Focus returns to invalid weight",weight.hasFocus());
                assertEquals("22.1",fat.getText().toString());assertEquals(before,store.all().size());
                weight.setText("70.3");fat.setText("99");
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                assertNotNull(fat.getError());assertTrue(fat.hasFocus());assertEquals(before,store.all().size());
                fat.setText("");((Calendar)read(activity,"manualTime")).add(Calendar.DAY_OF_MONTH,1);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                assertTrue(dialog.isShowing());assertEquals(before,store.all().size());
                dialog.dismiss();
            }
        });
    }

    public void testBackdatedSaveShowsSavedRecordAndSeparatesLocalFromSync() throws Throwable {
        WeighingActivity activity=getActivity();
        runTestOnUiThread(()->invoke(activity,"showManual"));
        getInstrumentation().waitForIdleSync();
        runTestOnUiThread(()->{
            ((EditText)read(activity,"manualWeight")).setText("68.4");
            Calendar selected=(Calendar)read(activity,"manualTime");selected.add(Calendar.DAY_OF_MONTH,-2);
            long selectedTime=selected.getTimeInMillis();
            ((AlertDialog)read(activity,"manualDialog")).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            savedId=(long)read(activity,"lastSavedId");assertTrue(savedId>0);
            try(LocalStore store=new LocalStore(activity)){
                Measurement saved=null;for(Measurement row:store.all())if(row.id==savedId)saved=row;
                assertNotNull(saved);assertEquals(selectedTime,saved.measuredAt);assertNull(saved.bodyFatPercent);
                assertEquals("仅本机 · 未加入 NAS 同步",NasSync.rowStatus(store,savedId));
            }
            assertEquals("68.4",((TextView)read(activity,"liveValue")).getText().toString());
            assertTrue(((TextView)read(activity,"liveCaption")).getText().toString().startsWith("刚保存"));
            assertTrue(((TextView)read(activity,"liveSource")).getText().toString().contains("已保存到本机"));
            activity.onLiveWeight(68.8);
            assertEquals(View.GONE,((View)read(activity,"liveDetail")).getVisibility());
            assertTrue(((TextView)read(activity,"liveSource")).getText().toString().contains("尚未保存"));
        });
    }

    public void testManualDraftRestoresWeightFatAndMeasurementTime() throws Throwable {
        WeighingActivity activity=getActivity();
        runTestOnUiThread(()->{
            invoke(activity,"showManual");
            ((EditText)read(activity,"manualWeight")).setText("71.2");
            ((EditText)read(activity,"manualFat")).setText("20.5");
            Calendar selected=(Calendar)read(activity,"manualTime");selected.add(Calendar.DAY_OF_MONTH,-4);
            long time=selected.getTimeInMillis();Bundle state=new Bundle();
            invokeBundle(activity,"onSaveInstanceState",state);
            ((AlertDialog)read(activity,"manualDialog")).dismiss();
            invokeBundle(activity,"showManual",state);
            assertEquals("71.2",((EditText)read(activity,"manualWeight")).getText().toString());
            assertEquals("20.5",((EditText)read(activity,"manualFat")).getText().toString());
            assertEquals(time,((Calendar)read(activity,"manualTime")).getTimeInMillis());
            ((AlertDialog)read(activity,"manualDialog")).dismiss();
        });
    }

    private static Object read(Object target,String name){try{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}catch(Exception error){throw new AssertionError(error);}}
    private static void invoke(Object target,String name){try{Method method=target.getClass().getDeclaredMethod(name);method.setAccessible(true);method.invoke(target);}catch(Exception error){throw new AssertionError(error);}}
    private static void invokeBundle(Object target,String name,Bundle state){try{Method method=target.getClass().getDeclaredMethod(name,Bundle.class);method.setAccessible(true);method.invoke(target,state);}catch(Exception error){throw new AssertionError(error);}}
}
