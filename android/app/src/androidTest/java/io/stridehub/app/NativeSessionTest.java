package io.stridehub.app;

import android.test.AndroidTestCase;
import android.database.Cursor;
import io.stridehub.app.scale.core.Measurement;
import io.stridehub.app.scale.data.LocalStore;
import io.stridehub.app.sync.NasSync;
import java.lang.reflect.Field;
import java.time.Instant;

/** Runs only in the dedicated debug test app; no server or personal scale is needed. */
@SuppressWarnings("deprecation")
public final class NativeSessionTest extends AndroidTestCase {
    @Override protected void setUp() throws Exception {
        super.setUp();
        assertTrue("Never run against the release app",getContext().getPackageName().endsWith(".debug"));
        NativeSession.clear(getContext());
        getContext().getSharedPreferences("connection",0).edit().clear().commit();
    }
    @Override protected void tearDown() throws Exception {
        NativeSession.clear(getContext());
        getContext().getSharedPreferences("connection",0).edit().clear().commit();
        super.tearDown();
    }
    private String expiry(){return Instant.now().plusSeconds(3600).toString();}
    private void processRestart() throws Exception {
        Field current=NativeSession.class.getDeclaredField("current");current.setAccessible(true);current.set(null,null);
        Field loaded=NativeSession.class.getDeclaredField("loaded");loaded.setAccessible(true);loaded.setBoolean(null,false);
    }
    public void testRememberedCredentialsAreEncryptedAndRestoreOnlyForTheirAccount() throws Exception {
        NativeSession.save(getContext(),"https://NAS.example:443/install/","alice","shmobile_secret_test",true,expiry());
        assertEquals("https://nas.example/install",NativeSession.server(getContext()).baseUrl());
        String disk=getContext().getSharedPreferences("native_session",0).getAll().toString();
        assertFalse("Credential must not be stored in plaintext",disk.contains("shmobile_secret_test"));
        processRestart();assertEquals("shmobile_secret_test",NativeSession.token(getContext()));
        getContext().getSharedPreferences("connection",0).edit().putString("username","bob").commit();
        processRestart();assertFalse(NativeSession.signedIn(getContext()));
    }
    public void testSessionOnlyCredentialDoesNotSurviveProcessRestart() throws Exception {
        NativeSession.save(getContext(),"https://nas.example","alice","shmobile_memory_only",false,expiry());
        assertTrue(NativeSession.signedIn(getContext()));
        assertTrue(getContext().getSharedPreferences("native_session",0).getAll().isEmpty());
        processRestart();assertFalse(NativeSession.signedIn(getContext()));
    }
    public void testExpiredOrTamperedCredentialRequiresNativeLogin() throws Exception {
        NativeSession.save(getContext(),"https://nas.example","alice","shmobile_secret",true,expiry());
        getContext().getSharedPreferences("native_session",0).edit().putString("expiresAt","2000-01-01T00:00:00Z").commit();
        processRestart();assertFalse(NativeSession.signedIn(getContext()));
        NativeSession.save(getContext(),"https://nas.example","alice","shmobile_secret",true,expiry());
        getContext().getSharedPreferences("native_session",0).edit().putString("token","invalid").commit();
        processRestart();assertFalse(NativeSession.signedIn(getContext()));
    }
    public void testSwitchAndLogoutPreserveScaleIdentityAndOriginalQueue() throws Exception {
        NasSync.initialize(getContext());
        NativeSession.save(getContext(),"https://nas.example/install","alice","shmobile_one",false,expiry());
        try(LocalStore store=new LocalStore(getContext())) {
            Measurement row=Measurement.create(null,System.currentTimeMillis(),70.2,null,0,"test",null,null);
            store.save(row);
            String uuid;
            try(Cursor c=store.getReadableDatabase().rawQuery("SELECT upload_uuid FROM measurements WHERE id=?",new String[]{""+row.id})){assertTrue(c.moveToFirst());uuid=c.getString(0);}
            try {
                store.getWritableDatabase().execSQL("INSERT INTO sync_queue(measurement_id,uuid,binding,server,payload,status) VALUES(?,?,?,?,?,?)",new Object[]{row.id,uuid,"alice-device","https://nas.example/install","{}","pending"});
                long before=NativeSession.generation();
                NativeSession.save(getContext(),"https://nas.example/install","bob","shmobile_two",false,expiry());
                NativeSession.clear(getContext());assertTrue(NativeSession.generation()>before);
                try(Cursor c=store.getReadableDatabase().rawQuery("SELECT m.upload_uuid,q.binding,q.status FROM measurements m JOIN sync_queue q ON q.measurement_id=m.id WHERE m.id=?",new String[]{""+row.id})) {
                    assertTrue(c.moveToFirst());assertEquals(uuid,c.getString(0));assertEquals("alice-device",c.getString(1));assertEquals("pending",c.getString(2));
                }
                assertEquals("同步已暂停 · 请返回原账号配对",NasSync.rowStatus(store,row.id));
            } finally {store.delete(row.id);}
        }
    }
}
