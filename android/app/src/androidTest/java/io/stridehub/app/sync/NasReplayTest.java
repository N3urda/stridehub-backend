package io.stridehub.app.sync;

import android.test.AndroidTestCase;
import android.database.Cursor;
import io.stridehub.app.scale.data.LocalStore;
import io.stridehub.app.scale.core.Measurement;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.*;

public final class NasReplayTest extends AndroidTestCase {
    private volatile Exception networkError;
    public void testExplicitRepairIdentityIsScopedAndSurvivesDisconnect(){
        String first=NasSync.pairingDevice(getContext(),"https://nas-one.example/install","alice");
        NasSync.disconnect(getContext());
        assertEquals(first,NasSync.pairingDevice(getContext(),"https://nas-one.example/install","alice"));
        assertFalse(first.equals(NasSync.pairingDevice(getContext(),"https://nas-one.example/install","bob")));
        assertFalse(first.equals(NasSync.pairingDevice(getContext(),"https://nas-two.example/install","alice")));
    }
    public void testWrongOrIncompleteReceiptsNeverAcknowledge()throws Exception {
        org.json.JSONObject measurement=new org.json.JSONObject().put("measurementId","uuid").put("deviceId","device").put("deleted",false);
        org.json.JSONObject receipt=new org.json.JSONObject().put("measurement",measurement).put("projection",new org.json.JSONObject().put("status","projected"));
        NasSync.validateReceipt(receipt,"uuid","device");
        for(int invalid=0;invalid<4;invalid++){
            org.json.JSONObject bad=new org.json.JSONObject(receipt.toString());
            if(invalid==0)bad.remove("measurement");
            if(invalid==1)bad.getJSONObject("measurement").put("measurementId","wrong");
            if(invalid==2)bad.getJSONObject("measurement").put("deviceId","other-device");
            if(invalid==3)bad.getJSONObject("measurement").put("deleted",true);
            try{NasSync.validateReceipt(bad,"uuid","device");fail("invalid receipt accepted");}catch(Exception expected){}
        }
    }
    private void bind(String server)throws Exception {
        getContext().getSharedPreferences("connection",0).edit().putString("server",server).putBoolean("http",true).commit();
        java.lang.reflect.Method seal=NasSync.class.getDeclaredMethod("seal",String.class);seal.setAccessible(true);
        getContext().getSharedPreferences("nas_sync",0).edit().clear().putString("server",server).putString("binding","test-device").putString("username",getContext().getSharedPreferences("connection",0).getString("username","")).putString("token",(String)seal.invoke(null,"test-only-token")).commit();
        NasSync.initialize(getContext());
        try(LocalStore store=new LocalStore(getContext())){
            android.database.sqlite.SQLiteDatabase db=store.getWritableDatabase();
            db.delete("sync_queue",null,null);db.delete("measurement_derivations",null,null);db.delete("legacy_imports",null,null);db.delete("measurements",null,null);
        }
    }
    private String row(LocalStore store,long id,String column){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT "+column+" FROM sync_queue WHERE measurement_id=?",new String[]{""+id})){assertTrue(c.moveToFirst());return c.getString(0);}}
    public void testUncertainReceiptFrozenReplayRestartRevocationAndSwitch()throws Exception {
        try(ServerSocket server=new ServerSocket(0)){
            server.setSoTimeout(20000);String address="http://127.0.0.1:"+server.getLocalPort();bind(address);
            long id;String frozen;
            try(LocalStore store=new LocalStore(getContext())){
                store.getWritableDatabase().delete("sync_queue",null,null);
                Measurement m=Measurement.create(null,System.currentTimeMillis(),71.2,null,0,"test",null,null);store.save(m);id=m.id;frozen=row(store,id,"payload");
            }
            ((android.app.job.JobScheduler)getContext().getSystemService(android.content.Context.JOB_SCHEDULER_SERVICE)).cancel(7341);
            Thread network=new Thread(()->{try{serve(server,0);serve(server,200);serve(server,401);}catch(Exception e){networkError=e;}});network.start();
            assertTrue(NasSync.run(getContext()));
            try(LocalStore store=new LocalStore(getContext())){assertEquals("pending",row(store,id,"status"));assertEquals(frozen,row(store,id,"payload"));}
            NasSync.run(getContext());
            try(LocalStore store=new LocalStore(getContext())){
                assertEquals("synced",row(store,id,"status"));assertEquals(frozen,row(store,id,"payload"));
                android.content.ContentValues values=new android.content.ContentValues();values.put("status","pending");store.getWritableDatabase().update("sync_queue",values,"measurement_id=?",new String[]{""+id});
            }
            assertFalse(NasSync.run(getContext()));
            try(LocalStore store=new LocalStore(getContext())){assertEquals("revoked",row(store,id,"status"));store.delete(id);}
            network.join(20000);assertFalse(network.isAlive());assertNull(networkError);
            getContext().getSharedPreferences("connection",0).edit().putString("server","http://127.0.0.1:1").commit();
            assertEquals("",NasSync.binding(getContext()));assertFalse(NasSync.run(getContext()));NasSync.disconnect(getContext());
        }
    }
    public void testDeletingLaterPendingRowWhileFirstRequestHeldNeverUploadsIt()throws Exception {
        try(ServerSocket server=new ServerSocket(0)){
            server.setSoTimeout(1500);bind("http://127.0.0.1:"+server.getLocalPort());
            long first,second;
            try(LocalStore store=new LocalStore(getContext())){
                store.getWritableDatabase().delete("sync_queue",null,null);
                Measurement a=Measurement.create(null,java.time.Instant.parse("2026-10-21T08:30:00Z").toEpochMilli(),71.2,null,0,"test",null,null);store.save(a);first=a.id;
                Measurement b=Measurement.create(null,a.measuredAt+123,71.3,null,0,"test",null,null);store.save(b);second=b.id;
                assertTrue(new org.json.JSONObject(row(store,first,"payload")).getString("measuredAt").matches(".*T[0-9]{2}:[0-9]{2}:00\\.000(?:Z|[+-][0-9]{2}:[0-9]{2})"));
            }
            ((android.app.job.JobScheduler)getContext().getSystemService(android.content.Context.JOB_SCHEDULER_SERVICE)).cancel(7341);
            java.util.concurrent.CountDownLatch received=new java.util.concurrent.CountDownLatch(1),resume=new java.util.concurrent.CountDownLatch(1);
            java.util.List<String> seen=java.util.Collections.synchronizedList(new java.util.ArrayList<>());
            Thread network=new Thread(()->{try{serve(server,200,received,resume,seen);try{serve(server,200,null,null,seen);}catch(java.net.SocketTimeoutException expected){}}catch(Exception e){networkError=e;}});
            Thread sync=new Thread(()->NasSync.run(getContext()));network.start();sync.start();
            assertTrue(received.await(5,java.util.concurrent.TimeUnit.SECONDS));
            try(LocalStore store=new LocalStore(getContext())){
                assertEquals("sending",row(store,first,"status"));store.delete(second);store.delete(first);
                assertEquals("cancelled_inflight",row(store,first,"status"));
            }
            resume.countDown();sync.join(5000);network.join(5000);assertFalse(sync.isAlive());assertFalse(network.isAlive());assertNull(networkError);assertEquals(1,seen.size());
            try(LocalStore store=new LocalStore(getContext())){assertEquals("deleted_local_received",row(store,first,"status"));store.getWritableDatabase().delete("sync_queue",null,null);}
            NasSync.disconnect(getContext());
        }
    }
    public void testConcurrentImportAndPairEnqueueCannotReverseDatabaseLockOrder()throws Exception {
        bind("http://127.0.0.1:1");
        LocalStore pairStore=new LocalStore(getContext());pairStore.getWritableDatabase();
        java.util.concurrent.CountDownLatch transactionHeld=new java.util.concurrent.CountDownLatch(1),resume=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
        Thread capture=new Thread(()->{try(LocalStore store=new LocalStore(getContext())){
            android.database.sqlite.SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
            try{db.execSQL("UPDATE measurements SET weight=weight");transactionHeld.countDown();resume.await(5,java.util.concurrent.TimeUnit.SECONDS);
                store.importCsv("记录ID,测量时间,记录时本地日期,体重kg,体脂率%,BMI,阻抗,来源,设备地址,测量时资料快照\nlock-test,2026-10-21T08:30:00+08:00,2026-10-21,70,,,0,手工,,\n");
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
        }catch(Throwable e){failure.set(e);}},"capture-transaction");
        capture.setDaemon(true);capture.start();assertTrue(transactionHeld.await(5,java.util.concurrent.TimeUnit.SECONDS));
        Thread pairEnqueue=new Thread(()->{try{NasSync.enqueueIfBound(pairStore);}catch(Throwable e){failure.set(e);}},"pair-enqueue");
        pairEnqueue.setDaemon(true);pairEnqueue.start();
        boolean reachedDatabase=false;long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while(!reachedDatabase&&System.nanoTime()<until){for(StackTraceElement frame:pairEnqueue.getStackTrace())if(frame.getClassName().contains("sqlite")&&(frame.getMethodName().contains("beginTransaction")||frame.getMethodName().contains("nativeExecute")))reachedDatabase=true;Thread.yield();}
        resume.countDown();assertTrue("pairing enqueue did not reach locked database: "+java.util.Arrays.toString(pairEnqueue.getStackTrace()),reachedDatabase);
        capture.join(5000);pairEnqueue.join(5000);assertFalse("capture deadlocked",capture.isAlive());assertFalse("pairing deadlocked",pairEnqueue.isAlive());assertNull(failure.get());pairStore.close();
        NasSync.disconnect(getContext());
    }
    private static void serve(ServerSocket server,int code)throws Exception {serve(server,code,null,null,null);}
    private static void serve(ServerSocket server,int code,java.util.concurrent.CountDownLatch receivedLatch,java.util.concurrent.CountDownLatch resume,java.util.List<String> seen)throws Exception {
        try(Socket socket=server.accept()){
            socket.setSoTimeout(5000);BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),java.nio.charset.StandardCharsets.ISO_8859_1));
            String line;int size=0;while((line=reader.readLine())!=null&&!line.isEmpty())if(line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))size=Integer.parseInt(line.substring(15).trim());
            java.io.ByteArrayOutputStream received=new java.io.ByteArrayOutputStream();
            while(size-->0)received.write(reader.read());
            String uuid=new org.json.JSONObject(received.toString("UTF-8")).getString("measurementId");
            if(seen!=null)seen.add(uuid);
            if(receivedLatch!=null){receivedLatch.countDown();if(!resume.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IOException("test transport wait timed out");}
            if(code==0)return;
            byte[] body=new org.json.JSONObject().put("measurement",new org.json.JSONObject().put("measurementId",uuid).put("deviceId","test-device").put("deleted",false)).put("projection",new org.json.JSONObject().put("status","projected")).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 "+code+" Result\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));out.write(body);out.flush();
        }
    }
}
