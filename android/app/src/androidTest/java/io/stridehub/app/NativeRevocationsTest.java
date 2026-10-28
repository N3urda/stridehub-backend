package io.stridehub.app;

import android.test.AndroidTestCase;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.lang.reflect.Field;
import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressWarnings("deprecation")
public final class NativeRevocationsTest extends AndroidTestCase {
    @Override protected void setUp() throws Exception {
        super.setUp();assertTrue(getContext().getPackageName().endsWith(".debug"));
        NativeSession.clear(getContext());
        getContext().getSharedPreferences("native_revocations",0).edit().clear().commit();
    }
    @Override protected void tearDown() throws Exception {
        NativeSession.clear(getContext());
        getContext().getSharedPreferences("native_revocations",0).edit().clear().commit();
        super.tearDown();
    }
    public void testLogoutQueueIsEncryptedDeduplicatedAndSurvivesLostResponseUntil401() throws Exception {
        String token="shmobile_disposable_outbox_secret";
        NativeSession.save(getContext(),"https://outbox.invalid/install","alice",token,true,null,true);
        String first=NativeRevocations.enqueue(getContext(),"https://outbox.invalid/install","alice",token);
        assertEquals(first,NativeRevocations.enqueue(getContext(),"https://outbox.invalid/install","alice",token));
        assertEquals(1,NativeRevocations.pendingCount(getContext()));
        assertFalse(getContext().getSharedPreferences("native_revocations",0).getAll().toString().contains(token));
        NativeSession.clear(getContext());
        AtomicInteger requests=new AtomicInteger();
        assertFalse(NativeRevocations.drain(getContext(),(server,credential)->{
            assertEquals("https://outbox.invalid/install",server);
            assertEquals(token,credential);
            requests.incrementAndGet();throw new IOException("response lost after server revoked");
        }));
        assertEquals(1,NativeRevocations.pendingCount(getContext()));
        assertTrue(NativeRevocations.drain(getContext(),(server,credential)->{requests.incrementAndGet();return 401;}));
        assertEquals(2,requests.get());assertEquals(0,NativeRevocations.pendingCount(getContext()));
        assertEquals("",NativeSession.candidateToken(getContext()));
    }
    public void testOldRevocationCompletionCannotClearOrRestoreANewerAccount() throws Exception {
        String id=NativeRevocations.enqueue(getContext(),"https://outbox.invalid","alice","shmobile_old");
        NativeSession.save(getContext(),"https://outbox.invalid","bob","shmobile_new",true,null,true);
        assertTrue(NativeRevocations.drain(getContext(),(server,credential)->204));
        assertEquals("shmobile_new",NativeSession.token(getContext()));
        assertEquals("bob",NativeSession.username(getContext()));
        assertFalse(NativeRevocations.isPending(getContext(),id));
    }
    public void testUnreachableFiveEntriesCannotStarveTheSixthRevocation() throws Exception {
        for(int i=0;i<6;i++)NativeRevocations.enqueue(getContext(),"https://outbox.invalid","alice","shmobile_fair_"+i);
        AtomicInteger sixth=new AtomicInteger();
        NativeRevocations.Transport transport=(server,credential)->{
            if(credential.equals("shmobile_fair_5")){sixth.incrementAndGet();return 204;}
            throw new IOException("unreachable");
        };
        assertFalse(NativeRevocations.drain(getContext(),transport));
        assertFalse(NativeRevocations.drain(getContext(),transport));
        assertEquals(1,sixth.get());assertEquals(5,NativeRevocations.pendingCount(getContext()));
    }
    public void testDurableLogoutIntentBlocksRestoreEvenWhenLocalClearCannotCommit() throws Exception {
        String token="shmobile_clear_failure";
        NativeSession.save(getContext(),"https://outbox.invalid","alice",token,true,null,true);
        NativeRevocations.enqueue(getContext(),"https://outbox.invalid","alice",token);
        Context failing=new ContextWrapper(getContext()) {
            @Override public SharedPreferences getSharedPreferences(String name,int mode) {
                SharedPreferences delegate=super.getSharedPreferences(name,mode);
                if(!name.equals("native_session"))return delegate;
                return (SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class<?>[]{SharedPreferences.class},(proxy,method,args)->{
                    if(!method.getName().equals("edit"))return method.invoke(delegate,args);
                    SharedPreferences.Editor original=delegate.edit();
                    return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),new Class<?>[]{SharedPreferences.Editor.class},(editor,action,values)->{
                        if(action.getName().equals("clear"))return editor;
                        if(action.getName().equals("commit"))return false;
                        Object result=action.invoke(original,values);
                        return result instanceof SharedPreferences.Editor ? editor : result;
                    });
                });
            }
        };
        NativeSession.clear(failing);
        assertFalse(getContext().getSharedPreferences("native_session",0).getString("token","").isEmpty());
        resetSessionMemory();assertEquals("",NativeSession.candidateToken(failing));
        assertEquals(1,NativeRevocations.pendingCount(getContext()));
        assertTrue(NativeRevocations.drain(getContext(),(server,credential)->204));
        resetSessionMemory();assertEquals("",NativeSession.candidateToken(failing));
    }
    private void resetSessionMemory() throws Exception {
        Field current=NativeSession.class.getDeclaredField("current");current.setAccessible(true);current.set(null,null);
        Field loaded=NativeSession.class.getDeclaredField("loaded");loaded.setAccessible(true);loaded.setBoolean(null,false);
    }

}
