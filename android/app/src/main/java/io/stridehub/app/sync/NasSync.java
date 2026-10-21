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
    private static final String PROCESS=java.util.UUID.randomUUID().toString();
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
            return p.getString("server","").equals(server(c).baseUrl())&&p.getString("username","").equals(c.getSharedPreferences("connection",0).getString("username",""))?p.getString("binding",""):"";
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
        prefs(c).edit().remove("binding").remove("token").remove("server").remove("attempt").remove("username").commit();
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
                    prefs(c).edit().putString("server",destination).putString("binding",device.getString("deviceId")).putString("username",username).putString("token",seal(device.getString("token"))).commit();
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
    public static void enqueueIfBound(LocalStore store){
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
                        /* Historical timezone is unknown: UTC represents the known instant only. */time=Measurement.formatCapturedTime(m.measuredAt,java.time.ZoneOffset.UTC);
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
            case "synced":return "NAS 已确认";
            case "sending":return "发送中，NAS 可能已收到";
            case "cancelled_inflight":return "本机已删除，发送中回执未确认";
            case "deleted_local_received":return "本机已删除，NAS 已收到，请在 NAS 删除副本";
            case "deleted_local_uncertain":return "本机已删除，NAS 可能已收到，请在 NAS 检查";
            case "revoked":return "设备已撤销，请重新配对";
            case "manual_conflict":return "NAS 日记录冲突，请检查";
            default:return "同步被拒绝，请检查 NAS";
        }
    }
    public static String rowStatus(LocalStore store,long id){
        try(Cursor row=store.getReadableDatabase().rawQuery("SELECT status,detail,binding FROM sync_queue WHERE measurement_id=?",new String[]{
            ""+id}
        )){
            if(!row.moveToFirst())return "仅本机 · 未加入 NAS 同步";
            String state=row.getString(0);
            if("pending".equals(state)&&app!=null&&!row.getString(2).equals(binding(app)))return "同步已暂停 · 请返回原账号配对";
            String detail=row.isNull(1)?"":row.getString(1);
            return label(state)+(detail.isEmpty()?"":" · "+detail);
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
            ContentValues recovered=new ContentValues();recovered.put("status","pending");recovered.putNull("claim_generation");
            db.update("sync_queue",recovered,"status='sending' AND (claim_generation IS NULL OR claim_generation<>?)",new String[]{PROCESS});
            for(int handled=0;handled<10;handled++){
                if(!b.equals(binding(c)))return false;
                Upload row=claim(db,b);
                if(row==null)break;
                try{
                    // Claim is the send boundary. Deletion after this point is explicitly in-flight.
                    if(!b.equals(binding(c))){release(db,row.id,"pending","连接已暂停");return false;}
                    JSONObject response=request(destination,"PUT","/device/v1/weight/"+row.uuid,row.payload,null,null,credential);
                    validateReceipt(response,row.uuid,b);
                    String projection=response.getJSONObject("projection").getString("status");
                    release(db,row.id,projection.equals("manual_conflict")?"manual_conflict":"synced",projection);
                }catch(HttpError e){
                    if(e.code==401||e.code==403){
                        release(db,row.id,"revoked","设备已撤销，请重新配对");
                        ContentValues revoked=new ContentValues();revoked.put("status","revoked");revoked.put("detail","设备已撤销，请重新配对");
                        db.update("sync_queue",revoked,"binding=? AND status='pending'",new String[]{b});return false;
                    }
                    if(e.code==408||e.code==429||e.code>=500){release(db,row.id,"pending","NAS 暂不可用，稍后自动重试");return true;}
                    release(db,row.id,"error",e.code==409?"冲突或 NAS 已删除；不可覆盖，请检查 NAS":"请求被拒绝 "+e.code);
                }catch(Exception e){release(db,row.id,"pending","连接中断，保留原记录稍后重试");return true;}
            }
            try(Cursor n=db.rawQuery("SELECT 1 FROM sync_queue WHERE binding=? AND status='pending' LIMIT 1",new String[]{
                b}
            )){
                return n.moveToFirst();
            }
        }
    }
    private static final class Upload {
        final long id;final String uuid,payload;
        Upload(long id,String uuid,String payload){this.id=id;this.uuid=uuid;this.payload=payload;}
    }
    /** A short DB transaction claims exactly one still-existing row; no cursor spans HTTP. */
    private static Upload claim(SQLiteDatabase db,String binding){
        db.beginTransaction();
        try{
            Upload result=null;
            try(Cursor row=db.rawQuery("SELECT q.measurement_id,q.uuid,q.payload FROM sync_queue q INNER JOIN measurements m ON m.id=q.measurement_id WHERE q.binding=? AND q.status='pending' ORDER BY q.measurement_id LIMIT 1",new String[]{binding})){
                if(row.moveToFirst()){
                    ContentValues claim=new ContentValues();claim.put("status","sending");claim.put("claim_generation",PROCESS);claim.put("detail","发送中，NAS 可能已收到");
                    if(db.update("sync_queue",claim,"measurement_id=? AND status='pending'",new String[]{Long.toString(row.getLong(0))})==1)result=new Upload(row.getLong(0),row.getString(1),row.getString(2));
                }
            }
            db.setTransactionSuccessful();return result;
        }finally{db.endTransaction();}
    }
    /** Deleting a claimed row prevents replay, even if receipt/timeout arrives afterwards. */
    private static void release(SQLiteDatabase db,long id,String status,String detail){
        db.beginTransaction();
        try{
            ContentValues value=new ContentValues();value.put("status",status);value.put("detail",detail);value.putNull("claim_generation");
            db.update("sync_queue",value,"measurement_id=? AND status='sending'",new String[]{Long.toString(id)});
            value.put("status",status.equals("synced")||status.equals("manual_conflict")?"deleted_local_received":"deleted_local_uncertain");
            value.put("detail","本机已删除，不会重试；请在 NAS 检查并删除可能的副本");
            db.update("sync_queue",value,"measurement_id=? AND status='cancelled_inflight'",new String[]{Long.toString(id)});
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
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
