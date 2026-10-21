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
        getContext().getSharedPreferences("nas_sync",0).edit().clear().putString("server",server).putString("binding","test-device").putString("token",(String)seal.invoke(null,"test-only-token")).commit();
        NasSync.initialize(getContext());
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
    private static void serve(ServerSocket server,int code)throws Exception {
        try(Socket socket=server.accept()){
            socket.setSoTimeout(5000);BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),java.nio.charset.StandardCharsets.ISO_8859_1));
            String line;int size=0;while((line=reader.readLine())!=null&&!line.isEmpty())if(line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))size=Integer.parseInt(line.substring(15).trim());
            java.io.ByteArrayOutputStream received=new java.io.ByteArrayOutputStream();
            while(size-->0)received.write(reader.read());
            String uuid=new org.json.JSONObject(received.toString("UTF-8")).getString("measurementId");
            if(code==0)return;
            byte[] body=new org.json.JSONObject().put("measurement",new org.json.JSONObject().put("measurementId",uuid).put("deviceId","test-device").put("deleted",false)).put("projection",new org.json.JSONObject().put("status","projected")).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 "+code+" Result\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));out.write(body);out.flush();
        }
    }
}
