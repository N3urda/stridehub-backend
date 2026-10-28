package io.stridehub.app;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;
import java.util.concurrent.locks.ReentrantLock;

/** Encrypted, durable logout intent; never reads or restores the current login. */
public final class NativeRevocations {
    private static final String PREFERENCES="native_revocations";
    private static final int JOB_ID=7342;
    private static final Object LOCK=new Object();
    private static final ReentrantLock WORKER=new ReentrantLock();
    private NativeRevocations(){}
    private static SharedPreferences prefs(Context context){return context.getSharedPreferences(PREFERENCES,Context.MODE_PRIVATE);}

    public static String prepare(Context context,String server,String username,String token) {
        String id=identifier(server,username,token);
        synchronized(LOCK) {
            boolean existed=prefs(context).contains(id);
            boolean marked=prefs(context).getBoolean("loggedout."+id,false);
            enqueue(context,server,username,token);
            if(!schedulePending(context)) {
                SharedPreferences.Editor rollback=prefs(context).edit();
                if(!existed)rollback.remove(id).remove("order."+id);
                if(!marked)rollback.remove("loggedout."+id);
                rollback.commit();
                throw new IllegalStateException("无法安排联网撤销，请稍后重试退出。");
            }
        }
        return id;
    }

    public static String enqueue(Context context,String server,String username,String token) {
        if(server==null || username==null || username.isEmpty() || token==null || token.isEmpty() || token.indexOf('\n')>=0 || token.indexOf('\r')>=0)
            throw new IllegalArgumentException("退出凭据无效。");
        ServerAddress address=ServerAddress.parse(server,true);
        if(!address.baseUrl().equals(server))throw new IllegalArgumentException("退出服务器地址不一致。");
        String id=identifier(server,username,token);
        synchronized(LOCK) {
            if(prefs(context).contains(id)) {
                if(!prefs(context).getBoolean("loggedout."+id,false) && !prefs(context).edit().putBoolean("loggedout."+id,true).commit())
                    throw new IllegalStateException("无法保存退出请求。");
                return id;
            }
            try {
                String payload=new JSONObject().put("server",server).put("username",username)
                    .put("token",NativeSession.encrypt(token,server,username)).toString();
                long order=prefs(context).getLong("sequence",0)+1;
                if(!prefs(context).edit().putString(id,payload).putBoolean("loggedout."+id,true)
                    .putLong("order."+id,order).putLong("sequence",order).commit())throw new IllegalStateException("无法保存退出请求。");
            } catch(Exception error){throw new IllegalStateException("无法安全保存退出请求，请重试。",error);}
        }
        return id;
    }

    public static int pendingCount(Context context){synchronized(LOCK){return entries(context).size();}}
    public static boolean isPending(Context context,String id){return prefs(context).contains(id);}
    public static boolean wasLoggedOut(Context context,String server,String username,String token) {
        return prefs(context).getBoolean("loggedout."+identifier(server,username,token),false);
    }
    private static List<Map.Entry<String,?>> entries(Context context) {
        List<Map.Entry<String,?>> result=new ArrayList<>();
        for(Map.Entry<String,?> entry:prefs(context).getAll().entrySet())
            if(entry.getKey().matches("[a-f0-9]{64}") && entry.getValue() instanceof String)result.add(entry);
        result.sort(Comparator.comparingLong((Map.Entry<String,?> e)->prefs(context).getLong("order."+e.getKey(),0)).thenComparing(Map.Entry::getKey));
        return result;
    }
    public static boolean schedulePending(Context context) {
        JobScheduler scheduler=(JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if(scheduler==null)return false;
        try {
            if(pendingCount(context)==0){scheduler.cancel(JOB_ID);return true;}
            JobInfo job=new JobInfo.Builder(JOB_ID,new ComponentName(context,NativeRevocationJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
                .setBackoffCriteria(30000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build();
            return scheduler.schedule(job)==JobScheduler.RESULT_SUCCESS;
        } catch(RuntimeException unavailable){return false;}
    }

    public static boolean drain(Context context){return drain(context,NativeRevocations::request);}
    interface Transport { int revoke(String server,String token)throws Exception; }
    static boolean drain(Context context,Transport transport) {
        if(!WORKER.tryLock())return false;
        try {
            List<Map.Entry<String,?>> pending;
            synchronized(LOCK){pending=entries(context);}
            int processed=0;
            for(Map.Entry<String,?> entry:pending) {
                if(processed++>=5)break;
                try {
                    String stored=(String)entry.getValue();
                    JSONObject value=new JSONObject(stored);
                    String server=value.getString("server"),username=value.getString("username");
                    String token=NativeSession.decrypt(value.getString("token"),server,username);
                    int status=transport.revoke(server,token);
                    if(status==204 || status==401) {
                        synchronized(LOCK) {
                            if(stored.equals(prefs(context).getString(entry.getKey(),null)) && !prefs(context).edit().remove(entry.getKey()).remove("order."+entry.getKey()).commit())return false;
                        }
                    }
                } catch(Exception unavailable) { /* Durable encrypted intent remains for the next network job. */ }
                synchronized(LOCK) {
                    if(prefs(context).contains(entry.getKey())) {
                        long next=prefs(context).getLong("sequence",0)+1;
                        prefs(context).edit().putLong("order."+entry.getKey(),next).putLong("sequence",next).commit();
                    }
                }
            }
            return pendingCount(context)==0;
        } finally {WORKER.unlock();}
    }

    private static int request(String server,String token)throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new java.net.URL(ServerAddress.parse(server,true).route("/mobile/v1/session")).openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10000);connection.setReadTimeout(15000);
            connection.setRequestMethod("DELETE");
            connection.setRequestProperty("Authorization","Bearer "+token);
            connection.setRequestProperty("Accept","application/json");
            connection.setRequestProperty("Cache-Control","no-store");
            int status=connection.getResponseCode();
            if(status==204)return status;
            // A proxy's HTML401 is not confirmation that this server credential is gone.
            if(status==401 && connection.getContentType()!=null && connection.getContentType().toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
                try(InputStream stream=connection.getErrorStream();ByteArrayOutputStream body=new ByteArrayOutputStream()) {
                    if(stream==null)return 0;
                    byte[] buffer=new byte[1024];int size;
                    while((size=stream.read(buffer))!=-1){body.write(buffer,0,size);if(body.size()>16384)return 0;}
                    String error=new JSONObject(body.toString("UTF-8")).optString("error","");
                    if("invalid_token".equals(error) || "invalid_credentials".equals(error))return 401;
                }
            }
            return 0;
        } finally {connection.disconnect();}
    }

    private static String identifier(String server,String username,String token) {
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest((server+"\0"+username+"\0"+token).getBytes(StandardCharsets.UTF_8));
            StringBuilder value=new StringBuilder();for(byte b:digest)value.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return value.toString();
        } catch(Exception error){throw new IllegalStateException("无法标识退出请求。",error);}
    }
}
