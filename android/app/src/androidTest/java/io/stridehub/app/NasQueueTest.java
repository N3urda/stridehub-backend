package io.stridehub.app;
import android.test.AndroidTestCase;
import android.database.Cursor;
import io.stridehub.app.scale.data.LocalStore;
import io.stridehub.app.scale.core.Measurement;
import io.stridehub.app.sync.NasSync;
public class NasQueueTest extends AndroidTestCase {
    public void testPausedReceiptRemainsLocalAndDoesNotChangeQueue(){
        NasSync.disconnect(getContext());NasSync.initialize(getContext());
        try(LocalStore store=new LocalStore(getContext())){
            Measurement m=Measurement.create(null,System.currentTimeMillis(),70.2,null,0,"test",null,null);store.save(m);
            try{
                store.getWritableDatabase().execSQL("INSERT INTO sync_queue(measurement_id,uuid,binding,server,payload,status) VALUES(?,?,?,?,?,?)",new Object[]{m.id,"receipt-test","original-account","https://nas.example","{}","pending"});
                assertEquals("同步已暂停 · 请返回原账号配对",NasSync.rowStatus(store,m.id));
                try(Cursor row=store.getReadableDatabase().rawQuery("SELECT status,binding FROM sync_queue WHERE measurement_id=?",new String[]{""+m.id})){assertTrue(row.moveToFirst());assertEquals("pending",row.getString(0));assertEquals("original-account",row.getString(1));}
                store.getWritableDatabase().execSQL("UPDATE sync_queue SET status='synced' WHERE measurement_id=?",new Object[]{m.id});
                assertEquals("NAS 已确认",NasSync.rowStatus(store,m.id));
            }finally{store.delete(m.id);}
        }
    }
    public void testOfflineRestartStableIdentityAndDelete(){
        NasSync.disconnect(getContext());NasSync.initialize(getContext());
        long id;String uuid;
        try(LocalStore store=new LocalStore(getContext())){
            Measurement m=Measurement.create(null,System.currentTimeMillis(),72.3,null,0,"test",null,null);store.save(m);id=m.id;
            try(Cursor c=store.getReadableDatabase().rawQuery("SELECT upload_uuid,captured_time FROM measurements WHERE id=?",new String[]{""+id})){assertTrue(c.moveToFirst());uuid=c.getString(0);assertNotNull(uuid);assertTrue(c.getString(1).contains("T"));}
        }
        try(LocalStore store=new LocalStore(getContext())){
            try(Cursor c=store.getReadableDatabase().rawQuery("SELECT upload_uuid FROM measurements WHERE id=?",new String[]{""+id})){assertTrue(c.moveToFirst());assertEquals(uuid,c.getString(0));}
            assertTrue(NasSync.status(getContext()).startsWith("仅本机 · 未配对 NAS"));store.delete(id);
            try(Cursor c=store.getReadableDatabase().rawQuery("SELECT 1 FROM measurements WHERE id=?",new String[]{""+id})){assertFalse(c.moveToFirst());}
        }
    }
}
