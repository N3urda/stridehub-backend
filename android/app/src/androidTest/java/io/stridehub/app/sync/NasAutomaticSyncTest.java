package io.stridehub.app.sync;

import android.app.job.JobScheduler;
import android.content.Context;
import android.database.Cursor;
import android.test.AndroidTestCase;
import io.stridehub.app.scale.core.Measurement;
import io.stridehub.app.scale.data.LocalStore;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Real SQLite, Keystore and loopback HTTP; no external service or personal records. */
public final class NasAutomaticSyncTest extends AndroidTestCase {
    @Override protected void setUp() throws Exception {
        super.setUp();
        NasSync.disconnect(getContext());
        drain();
        try(LocalStore store=new LocalStore(getContext())) {
            store.getWritableDatabase().delete("sync_queue",null,null);
            store.getWritableDatabase().delete("measurement_derivations",null,null);
            store.getWritableDatabase().delete("legacy_imports",null,null);
            store.getWritableDatabase().delete("measurements",null,null);
        }
    }
    @Override protected void tearDown() throws Exception {
        NasSync.disconnect(getContext());drain();super.tearDown();
    }
    private void drain() throws Exception {
        java.lang.reflect.Field work=NasSync.class.getDeclaredField("WORK");work.setAccessible(true);
        ((ExecutorService)work.get(null)).submit(()->{}).get(30,TimeUnit.SECONDS);
    }
    private void bind(int port) throws Exception {
        String address="http://127.0.0.1:"+port;
        getContext().getSharedPreferences("connection",0).edit().putString("server",address).putString("username","automatic-test").putBoolean("http",true).commit();
        java.lang.reflect.Method seal=NasSync.class.getDeclaredMethod("seal",String.class);seal.setAccessible(true);
        getContext().getSharedPreferences("nas_sync",0).edit().clear().putString("server",address).putString("username","automatic-test").putString("binding","test-device").putString("token",(String)seal.invoke(null,"test-only-token")).commit();
        NasSync.initialize(getContext());
    }
    private long save() {
        try(LocalStore store=new LocalStore(getContext())) {
            Measurement m=Measurement.create(null,System.currentTimeMillis(),72.4,null,0,"test",null,null);
            store.save(m);return m.id;
        }
    }
    private String value(long id,String column) {
        try(LocalStore store=new LocalStore(getContext());Cursor c=store.getReadableDatabase().rawQuery("SELECT "+column+" FROM sync_queue WHERE measurement_id=?",new String[]{Long.toString(id)})) {
            return c.moveToFirst()?c.getString(0):null;
        }
    }
    private void own(long id){
        try(LocalStore store=new LocalStore(getContext())){
            android.content.ContentValues owner=new android.content.ContentValues();owner.put("sync_owner",NasSync.queueOwner(getContext()));
            store.getWritableDatabase().update("measurements",owner,"id=?",new String[]{Long.toString(id)});
        }
    }
    public void testSavedCaptureUploadsImmediatelyWithoutManualAction() throws Exception {
        try(ServerSocket server=new ServerSocket(0)) {
            server.setSoTimeout(5000);bind(server.getLocalPort());
            CountDownLatch changed=new CountDownLatch(1);Runnable listener=changed::countDown;
            NasSync.addListener(listener);
            long id=save();
            // The old implementation only schedules a job with a 10-second minimum delay.
            try {
                try(Socket socket=server.accept()) {respond(socket,200);}
                drain();assertEquals("synced",value(id,"status"));
                assertTrue("visible status was not notified",changed.await(3,TimeUnit.SECONDS));
            } finally {NasSync.removeListener(listener);}
        }
    }
    public void testForegroundScreenUpdatesReceiptWithoutChangingTabs() throws Exception {
        boolean automatic=getContext().getSharedPreferences("local_profile",0).getBoolean("automaticWeighing",true);
        getContext().getSharedPreferences("local_profile",0).edit().putBoolean("automaticWeighing",false).commit();
        try(ServerSocket server=new ServerSocket(0)){
            server.setSoTimeout(5000);bind(server.getLocalPort());long id=save();
            try(Socket socket=server.accept();androidx.test.core.app.ActivityScenario<io.stridehub.app.scale.WeighingActivity> screen=androidx.test.core.app.ActivityScenario.launch(new android.content.Intent(getContext(),io.stridehub.app.scale.WeighingActivity.class))){
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(org.hamcrest.Matchers.containsString("发送中"))).check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()));
                respond(socket,200);drain();
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(org.hamcrest.Matchers.containsString("NAS 已确认"))).check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()));
                assertEquals("synced",value(id,"status"));
            }
        }finally{getContext().getSharedPreferences("local_profile",0).edit().putBoolean("automaticWeighing",automatic).commit();}
    }
    public void testOfflineFailureRetainsPayloadAndPersistentJobRecovers() throws Exception {
        try(ServerSocket server=new ServerSocket(0)) {
            server.setSoTimeout(5000);bind(server.getLocalPort());long id=save();String first;
            try(Socket socket=server.accept()){first=respond(socket,503);}
            drain();assertEquals("pending",value(id,"status"));
            android.app.job.JobInfo job=((JobScheduler)getContext().getSystemService(Context.JOB_SCHEDULER_SERVICE)).getPendingJob(7341);
            assertNotNull("offline retry must survive process death",job);assertTrue(job.isPersisted());assertEquals(android.app.job.JobInfo.NETWORK_TYPE_ANY,job.getNetworkType());
            CompletableFuture<Boolean> completed=new CompletableFuture<>();NasSync.runJob(getContext(),completed::complete);
            try(Socket socket=server.accept()){assertEquals(first,respond(socket,200));}
            assertFalse(completed.get(5,TimeUnit.SECONDS));assertEquals("synced",value(id,"status"));
        }
    }
    public void testDuplicateTriggersStaySerialAndIncludeCaptureSavedDuringUpload() throws Exception {
        try(ServerSocket server=new ServerSocket(0)) {
            server.setSoTimeout(5000);bind(server.getLocalPort());long first=save(),second;
            try(Socket socket=server.accept()){
                for(int i=0;i<30;i++)NasSync.requestAutomatic(getContext());
                second=save();
                assertEquals("sending",value(first,"status"));
                respond(socket,200);
            }
            try(Socket socket=server.accept()){respond(socket,200);}
            drain();assertEquals("synced",value(first,"status"));assertEquals("synced",value(second,"status"));
            assertNoUpload(server);
        }
    }
    public void testForegroundReconcilesOwnedUnqueuedHistoryWithoutPairRequest() throws Exception {
        long id=save();assertNull(value(id,"status"));
        try(ServerSocket server=new ServerSocket(0)) {
            server.setSoTimeout(5000);bind(server.getLocalPort());own(id);
            NasSync.onForeground(getContext());
            try {try(Socket socket=server.accept()){respond(socket,200);}drain();assertEquals("synced",value(id,"status"));}
            finally {NasSync.onBackground(getContext());}
        }
    }
    public void testUnpairedHistoryRequiresExplicitPairingConsent() throws Exception {
        long id=save();
        try(ServerSocket server=new ServerSocket(0)){
            bind(server.getLocalPort());NasSync.onForeground(getContext());
            try{drain();assertNull(value(id,"status"));assertNoUpload(server);}
            finally{NasSync.onBackground(getContext());}
            // This is the same consent-only path used after the native pairing confirmation.
            java.lang.reflect.Method enqueue=NasSync.class.getDeclaredMethod("enqueueIfBound",LocalStore.class,boolean.class);enqueue.setAccessible(true);
            try(LocalStore store=new LocalStore(getContext())){enqueue.invoke(null,store,true);}
            assertEquals("pending",value(id,"status"));
            NasSync.requestAutomatic(getContext());server.setSoTimeout(5000);
            try(Socket socket=server.accept()){respond(socket,200);}drain();assertEquals("synced",value(id,"status"));
        }
    }
    public void testAccountSwitchBeforeWorkerStartsKeepsEachCaptureWithItsOwner() throws Exception {
        try(ServerSocket original=new ServerSocket(0);ServerSocket next=new ServerSocket(0)){
            original.setSoTimeout(5000);next.setSoTimeout(5000);bind(original.getLocalPort());
            CountDownLatch release=new CountDownLatch(1),blocked=new CountDownLatch(1);
            java.lang.reflect.Field work=NasSync.class.getDeclaredField("WORK");work.setAccessible(true);
            ((ExecutorService)work.get(null)).execute(()->{blocked.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            assertTrue(blocked.await(3,TimeUnit.SECONDS));long first,second;
            try{
                first=save();NasSync.disconnect(getContext());bind(next.getLocalPort());second=save();
            }finally{release.countDown();}
            try(Socket socket=next.accept()){respond(socket,200);}drain();
            assertNull(value(first,"status"));assertEquals("synced",value(second,"status"));assertNoUpload(original);
            bind(original.getLocalPort());NasSync.requestAutomatic(getContext());original.setSoTimeout(5000);
            try(Socket socket=original.accept()){respond(socket,200);}drain();assertEquals("synced",value(first,"status"));
            assertTrue(value(first,"server").endsWith(":"+original.getLocalPort()));assertTrue(value(second,"server").endsWith(":"+next.getLocalPort()));
        }
    }
    public void testRevokedBindingNeverRetriesOrPairsAutomatically() throws Exception {
        try(ServerSocket server=new ServerSocket(0)) {
            server.setSoTimeout(5000);bind(server.getLocalPort());long first=save();
            try(Socket socket=server.accept()){respond(socket,401);}
            drain();assertEquals("revoked",value(first,"status"));
            long second=save();NasSync.onForeground(getContext());
            try {drain();assertEquals("revoked",value(second,"status"));assertEquals("test-device",NasSync.binding(getContext()));assertNoUpload(server);}
            finally {NasSync.onBackground(getContext());}
        }
    }
    public void testChangedAccountCannotUploadOriginalQueue() throws Exception {
        try(ServerSocket server=new ServerSocket(0)) {
            server.setSoTimeout(5000);bind(server.getLocalPort());long id=save();
            try(Socket socket=server.accept()){respond(socket,503);}drain();String payload=value(id,"payload");
            getContext().getSharedPreferences("connection",0).edit().putString("username","different-user").commit();
            NasSync.requestAutomatic(getContext());drain();
            assertEquals("",NasSync.binding(getContext()));assertEquals(payload,value(id,"payload"));assertEquals("pending",value(id,"status"));assertNoUpload(server);
        }
    }
    public void testQueueFailureCannotUndoCaptureAndForegroundRepairsIt() throws Exception {
        try(ServerSocket server=new ServerSocket(0)) {
            server.setSoTimeout(5000);bind(server.getLocalPort());
            try(LocalStore store=new LocalStore(getContext())){store.getWritableDatabase().execSQL("CREATE TRIGGER block_queue BEFORE INSERT ON sync_queue BEGIN SELECT RAISE(ABORT,'test queue failure'); END");}
            long id;
            try {id=save();drain();assertNull(value(id,"status"));try(LocalStore store=new LocalStore(getContext())){assertEquals(1,store.all().size());}}
            finally {try(LocalStore store=new LocalStore(getContext())){store.getWritableDatabase().execSQL("DROP TRIGGER block_queue");}}
            NasSync.onForeground(getContext());
            try {try(Socket socket=server.accept()){respond(socket,200);}drain();assertEquals("synced",value(id,"status"));}
            finally {NasSync.onBackground(getContext());}
        }
    }
    public void testStoppedJobFinishesReceiptButDoesNotClaimNextCapture() throws Exception {
        long first=save(),second=save();
        try(ServerSocket server=new ServerSocket(0)) {
            server.setSoTimeout(5000);bind(server.getLocalPort());
            own(first);own(second);
            java.util.concurrent.atomic.AtomicBoolean active=new java.util.concurrent.atomic.AtomicBoolean(true);
            CompletableFuture<Boolean> completed=new CompletableFuture<>();NasSync.runJob(getContext(),active::get,completed::complete);
            try(Socket socket=server.accept()){active.set(false);respond(socket,200);}
            assertTrue(completed.get(5,TimeUnit.SECONDS));assertEquals("synced",value(first,"status"));assertEquals("pending",value(second,"status"));assertNoUpload(server);
        }
    }
    private void assertNoUpload(ServerSocket server) throws Exception {
        server.setSoTimeout(700);
        try(Socket unexpected=server.accept()){fail("unexpected repeated upload or implicit pairing");}
        catch(SocketTimeoutException expected){}
    }
    private static String respond(Socket socket,int code) throws Exception {
        socket.setSoTimeout(5000);
        BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),java.nio.charset.StandardCharsets.ISO_8859_1));
        String line;int size=0;
        while((line=reader.readLine())!=null&&!line.isEmpty())if(line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))size=Integer.parseInt(line.substring(15).trim());
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();while(size-->0)bytes.write(reader.read());
        String request=bytes.toString("UTF-8");String uuid=new JSONObject(request).getString("measurementId");
        byte[] body=new JSONObject().put("measurement",new JSONObject().put("measurementId",uuid).put("deviceId","test-device").put("deleted",false)).put("projection",new JSONObject().put("status","projected")).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 "+code+" Result\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));out.write(body);out.flush();return request;
    }
}
