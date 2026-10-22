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
import io.stridehub.app.scale.ble.ScaleClient;
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

    public void testHistoricalWeightIsNotPresentedAsCurrentReading() throws Throwable {
        WeighingActivity activity=getActivity();
        runTestOnUiThread(()->{
            try(LocalStore store=new LocalStore(activity)){
                Measurement old=Measurement.create(null,System.currentTimeMillis()-86400000L,66.2,null,0,"手工录入",null,null);
                store.save(old);savedId=old.id;
            }
            invoke(activity,"render");
            assertEquals("—",((TextView)read(activity,"liveValue")).getText().toString());
            assertTrue(((TextView)read(activity,"liveCaption")).getText().toString().contains("等待读数"));
        });
    }

    public void testLiveReadingSurvivesNavigationWithoutLookingSaved() throws Throwable {
        WeighingActivity activity=getActivity();
        runTestOnUiThread(()->{
            activity.onStateChanged(ScaleClient.State.MEASURING,"正在称重");
            activity.onLiveWeight(69.3);
            write(activity,"page",1);invoke(activity,"render");
            write(activity,"page",0);invoke(activity,"render");
            assertEquals("69.3",((TextView)read(activity,"liveValue")).getText().toString());
            assertTrue(((TextView)read(activity,"liveSource")).getText().toString().contains("尚未保存"));
            assertNull(read(activity,"liveDetail"));
        });
    }

    public void testCandidateReturnsToWeighingAndIsNotSavedAutomatically() throws Throwable {
        WeighingActivity activity=getActivity();
        runTestOnUiThread(()->{
            try(LocalStore store=new LocalStore(activity)){
                int before=store.all().size();
                write(activity,"page",2);invoke(activity,"render");
                Measurement candidate=Measurement.create(null,System.currentTimeMillis(),70.8,null,0,"测试广播",null,null);
                activity.onStateChanged(ScaleClient.State.REVIEW,"请确认是否保存");
                activity.onConfirmationRequired(candidate,"未收到完整测量过程");
                assertEquals(0,read(activity,"page"));
                assertEquals("70.8",((TextView)read(activity,"liveValue")).getText().toString());
                assertEquals("这是我的，保存到手机",((TextView)read(activity,"measureButton")).getText().toString());
                assertEquals(before,store.all().size());
            }
        });
    }

    public void testDeletingCurrentResultClearsSavedState() throws Throwable {
        WeighingActivity activity=getActivity();
        runTestOnUiThread(()->{
            Measurement measurement=Measurement.create(null,System.currentTimeMillis(),67.1,null,0,"手工录入",null,null);
            activity.onMeasurement(measurement);savedId=measurement.id;
            assertTrue((boolean)read(activity,"sessionFinished"));
            try{Method delete=activity.getClass().getDeclaredMethod("deleteRecord",long.class);delete.setAccessible(true);delete.invoke(activity,savedId);}catch(Exception error){throw new AssertionError(error);}
            assertFalse((boolean)read(activity,"sessionFinished"));
            assertFalse((boolean)read(activity,"autoResume"));
            assertEquals(ScaleClient.State.IDLE,read(activity,"captureState"));
            assertEquals("—",((TextView)read(activity,"liveValue")).getText().toString());
        });
    }

    public void testExplicitPauseAndSettingsIntentSurviveSavedState() throws Throwable {
        WeighingActivity activity=getActivity();
        runTestOnUiThread(()->{
            invoke(activity,"showManual");((AlertDialog)read(activity,"manualDialog")).dismiss();
            write(activity,"externalSettings",true);write(activity,"permissionPending",true);
            Bundle state=new Bundle();invokeBundle(activity,"onSaveInstanceState",state);
            assertFalse(state.getBoolean("autoResume"));
            assertTrue(state.getBoolean("externalSettings"));assertTrue(state.getBoolean("permissionPending"));
        });
    }

    private static Object read(Object target,String name){try{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}catch(Exception error){throw new AssertionError(error);}}
    private static void write(Object target,String name,Object value){try{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}catch(Exception error){throw new AssertionError(error);}}
    private static void invoke(Object target,String name){try{Method method=target.getClass().getDeclaredMethod(name);method.setAccessible(true);method.invoke(target);}catch(Exception error){throw new AssertionError(error);}}
    private static void invokeBundle(Object target,String name,Bundle state){try{Method method=target.getClass().getDeclaredMethod(name,Bundle.class);method.setAccessible(true);method.invoke(target,state);}catch(Exception error){throw new AssertionError(error);}}
}
