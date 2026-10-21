package io.stridehub.app.sync;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.app.job.*;
import android.security.keystore.*;
import android.util.Base64;
import io.stridehub.app.ServerAddress;
import io.stridehub.app.scale.data.LocalStore;
import io.stridehub.app.scale.core.Measurement;
import org.json.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.security.KeyStore;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
/** Serialized, immutable uploads. Only pairing uses the browser session. */
public final class NasSync {
    private static final java.util.concurrent.ExecutorService WORK=Executors.newSingleThreadExecutor();
    private static Context app;
    private static SharedPreferences prefs(Context c){
        app=c.getApplicationContext();
        return app.getSharedPreferences("nas_sync",0);
    }
    public static ServerAddress server(Context c){
        SharedPreferences p=c.getSharedPreferences("connection",0);
        return ServerAddress.parse(p.getString("server",""),p.getBoolean("http",false));
    }
    public static synchronized String binding(Context c){
        SharedPreferences p=prefs(c);
        try{
            return p.getString("server","").equals(server(c).baseUrl())?p.getString("binding",""):"";
        }
        catch(Exception e){
            return "";
        }
    }
    private static javax.crypto.SecretKey key()throws Exception{
        KeyStore k=KeyStore.getInstance("AndroidKeyStore");
        k.load(null);
        if(!k.containsAlias("nas-device")){
            KeyGenerator g=KeyGenerator.getInstance("AES","AndroidKeyStore");
            g.init(new KeyGenParameterSpec.Builder("nas-device",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            g.generateKey();
        }
        return (javax.crypto.SecretKey)k.getKey("nas-device",null);
    }
    private static String seal(String token)throws Exception{
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE,key());
        return Base64.encodeToString(c.getIV(),2)+":"+Base64.encodeToString(c.doFinal(token.getBytes(StandardCharsets.UTF_8)),2);
    }
    private static String token(Context c)throws Exception{
        String[] parts=prefs(c).getString("token","").split(":");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(parts[0],2)));
        return new String(cipher.doFinal(Base64.decode(parts[1],2)),StandardCharsets.UTF_8);
    }
    public static synchronized void disconnect(Context c){
        prefs(c).edit().remove("binding").remove("token").remove("server").remove("attempt").commit();
        ((JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE)).cancel(7341);
    }
    public interface Done{
        void done(String message);
    }
    static synchronized String pairingDevice(Context c,String destination,String username){
        String identity=java.util.UUID.nameUUIDFromBytes((destination+"\n"+username).getBytes(StandardCharsets.UTF_8)).toString();
        String preference="device."+identity;
        String device=prefs(c).getString(preference,null);
        if(device==null){device=java.util.UUID.randomUUID().toString().replace("-","");prefs(c).edit().putString(preference,device).commit();}
        return device;
    }
    public static void pair(Context c,String cookie,Done done){
        final String generation=java.util.UUID.randomUUID().toString();
        final String destination=server(c).baseUrl();
        final String username=c.getSharedPreferences("connection",0).getString("username","");
        final String deviceId=pairingDevice(c,destination,username);
        prefs(c).edit().putString("attempt",generation).commit();
        WORK.execute(()->{
            try{
                ServerAddress s=ServerAddress.parse(destination,c.getSharedPreferences("connection",0).getBoolean("http",false));
                JSONObject csrf=request(s,"GET","/admin/health/api/collection/csrf",null,cookie,null,null);
                JSONObject device=request(s,"POST","/admin/health/api/collection/devices",new JSONObject().put("name","StrideHub Android").put("deviceId",deviceId).toString(),cookie,csrf.getString("csrfToken"),null);
                synchronized(NasSync.class){
                    if(!generation.equals(prefs(c).getString("attempt",""))||!destination.equals(server(c).baseUrl())||!username.equals(c.getSharedPreferences("connection",0).getString("username","")))throw new Exception("连接已取消，请重新配对");
                    prefs(c).edit().putString("server",destination).putString("binding",device.getString("deviceId")).putString("token",seal(device.getString("token"))).commit();
                }
                try(LocalStore store=new LocalStore(c)){
                    ContentValues recover=new ContentValues();recover.put("status","pending");recover.putNull("detail");
                    store.getWritableDatabase().update("sync_queue",recover,"binding=? AND status='revoked'",new String[]{deviceId});
                    enqueueIfBound(store);
                }
                schedule(c);
                done.done("配对成功，记录已加入同步队列");
            }
            catch(Exception e){
                done.done("配对失败："+e.getMessage());
            }
        }
        );
    }
    public static void initialize(Context c){
        prefs(c);
    }
    public static synchronized void enqueueIfBound(LocalStore store){
        if(app==null)return;
        String b=binding(app);
        if(b.isEmpty())return;
        SQLiteDatabase db=store.getWritableDatabase();
        db.beginTransaction();
        try{
            for(Measurement m:store.all()){
                try(Cursor cursor=db.rawQuery("SELECT upload_uuid,captured_time FROM measurements WHERE id=?",new String[]{
                    ""+m.id}
                )){
                    if(!cursor.moveToFirst())continue;
                    String uuid=cursor.getString(0),time=cursor.getString(1);
                    if(uuid==null){
                        uuid=java.util.UUID.randomUUID().toString();
                        /* Historical timezone is unknown: UTC represents the known instant only. */time=java.time.Instant.ofEpochMilli(m.measuredAt).atOffset(java.time.ZoneOffset.UTC).toString();
                        ContentValues v=new ContentValues();
                        v.put("upload_uuid",uuid);
                        v.put("captured_time",time);
                        db.update("measurements",v,"id=?",new String[]{
                            ""+m.id}
                        );
                    }
                    JSONObject payload=new JSONObject();
                    payload.put("measurementId",uuid);
                    payload.put("measuredAt",time);
                    payload.put("localDate",m.localDay);
                    payload.put("weightKg",m.weightKg);
                    payload.put("fatPercent",m.bodyFatPercent==null?JSONObject.NULL:m.bodyFatPercent);
                    payload.put("bmi",m.bmi==null?JSONObject.NULL:m.bmi);
                    payload.put("impedanceOhms",m.impedance<=0?JSONObject.NULL:m.impedance);
                    payload.put("profile",m.profileSnapshot==null?new JSONObject():new JSONObject(m.profileSnapshot));
                    JSONObject values=new JSONObject();
                    if(m.metrics!=null)for(java.util.Map.Entry<io.stridehub.app.scale.core.BodyMetrics.Metric,Double> entry:m.metrics.values.entrySet())values.put(entry.getKey().name(),entry.getValue());
                    JSONObject derived=new JSONObject().put("algorithmVersion","yunmai-"+(m.metrics==null?"unavailable":m.metrics.algorithmVersion)).put("values",values);
                    derived.put("source",m.source);
                    derived.put("calculatedAt",m.metricsCalculatedAt);
                    derived.put("profile",m.metricsProfileSnapshot==null?new JSONObject():new JSONObject(m.metricsProfileSnapshot));
                    if(m.metrics!=null){
                        derived.put("calculationBasis",m.metrics.calculationBasis);
                        derived.put("bodyShape",m.metrics.bodyShape);
                        JSONObject grades=new JSONObject(),references=new JSONObject(),scores=new JSONObject();
                        for(java.util.Map.Entry<io.stridehub.app.scale.core.BodyMetrics.Metric,String> entry:m.metrics.grades.entrySet())grades.put(entry.getKey().name(),entry.getValue());
                        for(java.util.Map.Entry<io.stridehub.app.scale.core.BodyMetrics.Metric,String> entry:m.metrics.references.entrySet())references.put(entry.getKey().name(),entry.getValue());
                        for(java.util.Map.Entry<io.stridehub.app.scale.core.BodyMetrics.Metric,Double> entry:m.metrics.scores.entrySet())scores.put(entry.getKey().name(),entry.getValue());
                        derived.put("grades",grades).put("references",references).put("scores",scores);
                    }
                    payload.put("derived",derived);
                    ContentValues v=new ContentValues();
                    v.put("measurement_id",m.id);
                    v.put("uuid",uuid);
                    v.put("binding",b);
                    v.put("server",server(app).baseUrl());
                    v.put("payload",payload.toString());
                    db.insertWithOnConflict("sync_queue",null,v,SQLiteDatabase.CONFLICT_IGNORE);
                }
            }
            db.setTransactionSuccessful();
        }
        catch(Exception e){
            throw new IllegalStateException("无法建立同步队列",e);
        }
        finally{
            db.endTransaction();
        }
        schedule(app);
    }
    private static String label(String state){
        switch(state){
            case "pending":return "待同步";
            case "synced":return "已同步";
            case "revoked":return "设备已撤销，请重新配对";
            case "manual_conflict":return "NAS 日记录冲突，请检查";
            default:return "同步被拒绝，请检查 NAS";
        }
    }
    public static String rowStatus(LocalStore store,long id){
        try(Cursor row=store.getReadableDatabase().rawQuery("SELECT status,detail FROM sync_queue WHERE measurement_id=?",new String[]{
            ""+id}
        )){
            return row.moveToFirst()?label(row.getString(0))+" · "+(row.isNull(1)?"":row.getString(1)):"仅本机";
        }
    }
    public static String status(Context c){
        initialize(c);
        String b=binding(c);
        StringBuilder out=new StringBuilder(b.isEmpty()?"仅本机 · 未配对 NAS\n":"NAS "+prefs(c).getString("server","")+"\n");
        try(LocalStore store=new LocalStore(c);
        Cursor cur=store.getReadableDatabase().rawQuery("SELECT status,count(*) FROM sync_queue GROUP BY status",null)){
            while(cur.moveToNext())out.append(label(cur.getString(0))).append(": ").append(cur.getInt(1)).append("  ");
        }
        return out.append("\n含已暂停的原账号记录；返回原服务器和原登录名并确认配对，可恢复原设备。其他账号不会接管旧队列。").toString();
    }
    public static void schedule(Context c){
        if(binding(c).isEmpty())return;
        ((JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE)).schedule(new JobInfo.Builder(7341,new ComponentName(c,SyncJob.class)).setMinimumLatency(10000).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setBackoffCriteria(30000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build());
    }
    public static void sync(Context c,Done done){
        WORK.execute(()->{
            if(binding(c).isEmpty()){done.done("尚未配对；原账号队列已暂停，记录继续保存在本机");return;}
            boolean retry=run(c);if(retry)schedule(c);
            done.done(retry?"仍有待同步记录；稍后自动重试":"本轮处理结束，请查看记录同步状态");
        });
    }
    static void runJob(Context c,java.util.function.Consumer<Boolean> done){
        WORK.execute(()->done.accept(run(c)));
    }
    static boolean run(Context c){
        String b=binding(c);
        if(b.isEmpty())return false;
        final ServerAddress destination;
        final String credential;
        try{
            synchronized(NasSync.class){
                if(!b.equals(binding(c)))return false;
                destination=server(c);
                credential=token(c);
            }
        }
        catch(Exception invalid){
            return false;
        }
        try(LocalStore store=new LocalStore(c)){
            SQLiteDatabase db=store.getWritableDatabase();
            try(Cursor rows=db.rawQuery("SELECT measurement_id,uuid,payload FROM sync_queue WHERE binding=? AND status='pending' LIMIT 10",new String[]{
                b}
            )){
                while(rows.moveToNext()){
                    {
                        if(!b.equals(binding(c)))return false;
                        try{
                            JSONObject response=request(destination,"PUT","/device/v1/weight/"+rows.getString(1),rows.getString(2),null,null,credential);
                            if(!b.equals(binding(c)))return false;
                            validateReceipt(response,rows.getString(1),b);
                            String projection=response.getJSONObject("projection").getString("status");
                            update(db,rows.getLong(0),projection.equals("manual_conflict")?"manual_conflict":"synced",projection);
                        }
                        catch(HttpError e){
                            if(e.code==401||e.code==403){
                                ContentValues revoked=new ContentValues();
                                revoked.put("status","revoked");
                                revoked.put("detail","设备已撤销，请断开后重新配对");
                                db.update("sync_queue",revoked,"binding=? AND status='pending'",new String[]{
                                    b}
                                );
                                return false;
                            }
                            if(e.code==408||e.code==429){
                                update(db,rows.getLong(0),"pending","NAS 暂忙，稍后自动重试");
                                return true;
                            }
                            if(e.code>=300&&e.code<500)update(db,rows.getLong(0),"error",e.code==409?"冲突或 NAS 已删除；不可覆盖，请检查 NAS":"请求被拒绝 "+e.code);
                            else {
                                update(db,rows.getLong(0),"pending","NAS 暂不可用，稍后自动重试");
                                return true;
                            }
                        }
                        catch(Exception e){
                            update(db,rows.getLong(0),"pending","连接中断，保留原记录稍后重试");
                            return true;
                        }
                    }
                }
            }
            try(Cursor n=db.rawQuery("SELECT 1 FROM sync_queue WHERE binding=? AND status='pending' LIMIT 1",new String[]{
                b}
            )){
                return n.moveToFirst();
            }
        }
    }
    static void validateReceipt(JSONObject response,String uuid,String device)throws Exception {
        JSONObject measurement=response.getJSONObject("measurement");
        if(!uuid.equals(measurement.getString("measurementId"))||!device.equals(measurement.getString("deviceId"))||measurement.getBoolean("deleted"))throw new Exception("NAS 回执不匹配，保留原队列");
        String status=response.getJSONObject("projection").getString("status");
        if(!status.equals("projected")&&!status.equals("unchanged")&&!status.equals("manual_conflict"))throw new Exception("NAS 回执状态不明确，保留原队列");
    }
    private static void update(SQLiteDatabase db,long id,String status,String detail){
        ContentValues v=new ContentValues();
        v.put("status",status);
        v.put("detail",detail);
        db.update("sync_queue",v,"measurement_id=?",new String[]{
            ""+id}
        );
    }
    private static class HttpError extends Exception{
        final int code;
        HttpError(int c){
            super("HTTP "+c);
            code=c;
        }
    }
    private static JSONObject request(ServerAddress server,String method,String path,String body,String cookie,String csrf,String bearer)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(server.route(path)).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestMethod(method);
        if(cookie!=null)c.setRequestProperty("Cookie",cookie);
        if(csrf!=null)c.setRequestProperty("X-CSRF-Token",csrf);
        if(bearer!=null)c.setRequestProperty("Authorization","Bearer "+bearer);
        try{
            if(body!=null){
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type","application/json");
                try(java.io.OutputStream out=c.getOutputStream()){
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code=c.getResponseCode();
            if(code<200||code>=300)throw new HttpError(code);
            try(java.io.InputStream in=c.getInputStream()){
                java.io.ByteArrayOutputStream buffer=new java.io.ByteArrayOutputStream();
                byte[] chunk=new byte[4096];
                int count;
                while((count=in.read(chunk))!=-1){
                    buffer.write(chunk,0,count);
                    if(buffer.size()>65536)throw new Exception("响应过大");
                }
                byte[] bytes=buffer.toByteArray();
                if(bytes.length>65536)throw new Exception("响应过大");
                return new JSONObject(new String(bytes,StandardCharsets.UTF_8));
            }
        }
        finally{
            c.disconnect();
        }
    }
}
