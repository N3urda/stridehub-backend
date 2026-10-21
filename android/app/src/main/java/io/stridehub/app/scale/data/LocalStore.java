package io.stridehub.app.scale.data;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import io.stridehub.app.scale.core.BodyComposition;
import io.stridehub.app.scale.core.BodyMetrics;
import io.stridehub.app.scale.core.BodyMetrics.Metric;
import io.stridehub.app.scale.core.Measurement;
import io.stridehub.app.scale.core.Profile;
import io.stridehub.app.scale.core.YunmaiProtocol;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.time.LocalDate;
import java.time.ZoneId;

public final class LocalStore extends SQLiteOpenHelper {
    private final SharedPreferences preferences;
    public LocalStore(Context context) {
        super(context,"tinyyumi.db",null,3);
        preferences=context.getSharedPreferences("local_profile",Context.MODE_PRIVATE);
    }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE measurements (id INTEGER PRIMARY KEY AUTOINCREMENT, measured_at INTEGER NOT NULL, local_day TEXT NOT NULL, weight REAL NOT NULL, fat REAL, impedance INTEGER NOT NULL DEFAULT 0, bmi REAL, source TEXT NOT NULL, device_address TEXT, raw_packet TEXT, profile_snapshot TEXT)");
        db.execSQL("CREATE INDEX measurement_time ON measurements(measured_at DESC,id DESC)");
        createDerivations(db);
        createImports(db);
    }
    private void createDerivations(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS measurement_derivations (measurement_id INTEGER NOT NULL, algorithm_version INTEGER NOT NULL, calculated_at INTEGER NOT NULL, profile_snapshot TEXT, metrics_json TEXT NOT NULL, PRIMARY KEY(measurement_id,algorithm_version))");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) {
        if(oldVersion<2)createDerivations(db);
        if(oldVersion<3)createImports(db);
    }
    private void createImports(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE legacy_imports (fingerprint TEXT PRIMARY KEY, measurement_id INTEGER NOT NULL, original_row TEXT NOT NULL)");
    }
    /** One transaction: malformed imports cannot leave a partially migrated history. */
    public synchronized int importCsv(String csv) throws Exception {
        List<List<String>> rows=io.stridehub.app.scale.core.LegacyCsv.parse(csv);
        SQLiteDatabase db=getWritableDatabase();int inserted=0;db.beginTransaction();
        try {
            for(int i=1;i<rows.size();i++) {
                List<String> row=rows.get(i);
                String original=new org.json.JSONArray(row).toString();
                byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(original.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder key=new StringBuilder();for(byte b:hash)key.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
                try(Cursor c=db.rawQuery("SELECT 1 FROM legacy_imports WHERE fingerprint=?",new String[]{key.toString()})){if(c.moveToFirst())continue;}
                Measurement m=io.stridehub.app.scale.core.LegacyCsv.measurement(row);
                // The original export row retains all historical derived values and metadata.
                // Retain historical estimates, calculation time and profile exactly.
                io.stridehub.app.scale.core.LegacyCsv.restoreDerived(m,rows.get(0),row);
                save(m);
                ContentValues values=new ContentValues();values.put("fingerprint",key.toString());values.put("measurement_id",m.id);values.put("original_row",original);
                db.insertOrThrow("legacy_imports",null,values);inserted++;
            }
            db.setTransactionSuccessful();return inserted;
        } finally {db.endTransaction();}
    }
    public synchronized void save(Measurement m) {
        ContentValues values=new ContentValues();
        values.put("measured_at",m.measuredAt);values.put("local_day",m.localDay);values.put("weight",m.weightKg);
        values.put("fat",m.bodyFatPercent);values.put("impedance",m.impedance);values.put("bmi",m.bmi);
        values.put("source",m.source);values.put("device_address",m.deviceAddress);
        values.put("raw_packet",m.rawPacket);values.put("profile_snapshot",m.profileSnapshot);
        m.id=getWritableDatabase().insertOrThrow("measurements",null,values);
        // Raw capture is durable first. A failed derivation never discards it or prevents the BLE ACK.
        ensureDerived(m);
    }
    public synchronized List<Measurement> all() {
        List<Measurement> result=new ArrayList<>();
        String query="SELECT m.id,m.measured_at,m.local_day,m.weight,m.fat,m.impedance,m.bmi,m.source,m.device_address,m.raw_packet,m.profile_snapshot,d.metrics_json,d.calculated_at,d.profile_snapshot FROM measurements m LEFT JOIN measurement_derivations d ON d.measurement_id=m.id AND d.algorithm_version=? ORDER BY m.measured_at DESC,m.id DESC";
        try(Cursor c=getReadableDatabase().rawQuery(query,new String[]{Integer.toString(BodyMetrics.ALGORITHM_VERSION)})) {
            while(c.moveToNext()) {
                Measurement m=new Measurement();
                m.id=c.getLong(0);m.measuredAt=c.getLong(1);m.localDay=c.getString(2);m.weightKg=c.getDouble(3);
                m.bodyFatPercent=c.isNull(4)?null:c.getDouble(4);m.impedance=c.getInt(5);m.bmi=c.isNull(6)?null:c.getDouble(6);
                m.source=c.getString(7);m.deviceAddress=c.getString(8);m.rawPacket=c.getString(9);m.profileSnapshot=c.getString(10);
                if(!c.isNull(11)) {
                    m.metrics=decode(c.getString(11));m.metricsCalculatedAt=c.getLong(12);m.metricsProfileSnapshot=c.getString(13);
                }
                result.add(m);
            }
        }
        // Only absent results are backfilled, always from the captured profile and date.
        for(Measurement m:result)if(m.metrics==null)ensureDerived(m);
        return result;
    }
    private void ensureDerived(Measurement m) {
        try {
            if(m.metrics==null||m.metrics.algorithmVersion!=BodyMetrics.ALGORITHM_VERSION) {
                long calculationTime=calculationTime(m);
                Profile p=profileFromSnapshot(m.profileSnapshot,calculationTime);
                Double fat=m.bodyFatPercent;String basis=m.source;
                if(fat==null){fat=YunmaiProtocol.estimateHistoricalFat(m,p,calculationTime);if(fat!=null)basis=(basis==null?"未知来源":basis)+" · 历史阻抗补算";}
                m.metrics=BodyComposition.calculate(p,calculationTime,m.weightKg,fat,m.impedance,basis);
                m.metricsCalculatedAt=System.currentTimeMillis();
                m.metricsProfileSnapshot=p==null?null:m.profileSnapshot;
            }
            if(m.metrics==null)return;
            if(m.metricsCalculatedAt<=0)m.metricsCalculatedAt=System.currentTimeMillis();
            ContentValues values=new ContentValues();values.put("measurement_id",m.id);
            values.put("algorithm_version",m.metrics.algorithmVersion);values.put("calculated_at",m.metricsCalculatedAt);
            values.put("profile_snapshot",m.metricsProfileSnapshot);values.put("metrics_json",encode(m.metrics));
            getWritableDatabase().insertWithOnConflict("measurement_derivations",null,values,SQLiteDatabase.CONFLICT_REPLACE);
        }catch(RuntimeException|JSONException ignored) {
            // Weight/history remain available; a later read retries missing estimates.
        }
    }
    private String encode(BodyMetrics metrics)throws JSONException {
        JSONObject json=new JSONObject(),values=new JSONObject(),grades=new JSONObject(),references=new JSONObject(),scores=new JSONObject();
        for(Map.Entry<Metric,Double> entry:metrics.values.entrySet())if(entry.getValue()!=null&&Double.isFinite(entry.getValue()))values.put(entry.getKey().name(),entry.getValue());
        for(Map.Entry<Metric,String> entry:metrics.grades.entrySet())grades.put(entry.getKey().name(),entry.getValue());
        for(Map.Entry<Metric,String> entry:metrics.references.entrySet())references.put(entry.getKey().name(),entry.getValue());
        for(Map.Entry<Metric,Double> entry:metrics.scores.entrySet())if(entry.getValue()!=null&&Double.isFinite(entry.getValue()))scores.put(entry.getKey().name(),entry.getValue());
        json.put("values",values);json.put("grades",grades);json.put("references",references);json.put("scores",scores);
        json.put("bodyShape",metrics.bodyShape);json.put("calculationBasis",metrics.calculationBasis);json.put("algorithmVersion",metrics.algorithmVersion);
        return json.toString();
    }
    private BodyMetrics decode(String encoded) {
        try {
            JSONObject json=new JSONObject(encoded);BodyMetrics metrics=new BodyMetrics();
            metrics.algorithmVersion=json.getInt("algorithmVersion");
            if(metrics.algorithmVersion!=BodyMetrics.ALGORITHM_VERSION)return null;
            JSONObject values=json.getJSONObject("values"),grades=json.optJSONObject("grades"),references=json.optJSONObject("references"),scores=json.optJSONObject("scores");
            for(Metric metric:Metric.values()) {
                if(scores!=null&&scores.has(metric.name())&&!scores.isNull(metric.name())){double score=scores.getDouble(metric.name());if(Double.isFinite(score))metrics.scores.put(metric,score);}
                if(values.has(metric.name())&&!values.isNull(metric.name()))metrics.put(metric,values.getDouble(metric.name()));
                if(grades!=null&&grades.has(metric.name())&&!grades.isNull(metric.name()))metrics.grades.put(metric,grades.getString(metric.name()));
                if(references!=null&&references.has(metric.name())&&!references.isNull(metric.name()))metrics.references.put(metric,references.getString(metric.name()));
            }
            metrics.bodyShape=json.isNull("bodyShape")?null:json.optString("bodyShape",null);
            metrics.calculationBasis=json.isNull("calculationBasis")?null:json.optString("calculationBasis",null);
            return metrics;
        }catch(JSONException|RuntimeException invalid){return null;}
    }
    /** A captured calendar day keeps age stable when a phone later changes timezone. */
    public static long calculationTime(Measurement measurement) {
        try{return LocalDate.parse(measurement.localDay).atTime(12,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();}
        catch(RuntimeException invalid){return measurement.measuredAt;}
    }
    public static Profile profileFromSnapshot(String snapshot,long when) {
        if(snapshot==null||snapshot.isEmpty())return null;
        try {
            JSONObject json=new JSONObject(snapshot);
            Profile profile=new Profile(json.getInt("heightCm"),json.getString("birthDate"),json.getBoolean("male"),json.getInt("userId"));
            return profile.validationError(when)==null?profile:null;
        }catch(JSONException|RuntimeException invalid){return null;}
    }
    public synchronized void delete(long id) {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            String[] args={Long.toString(id)};db.delete("measurement_derivations","measurement_id=?",args);
            db.delete("measurements","id=?",args);db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public Profile profile() {
        if(!preferences.getBoolean("complete",false))return null;
        return new Profile(preferences.getInt("height",0),preferences.getString("birth",null),preferences.getBoolean("male",false),preferences.getInt("userId",1));
    }
    public void saveProfile(Profile p) {
        preferences.edit().putInt("height",p.heightCm).putString("birth",p.birthDate).putBoolean("male",p.male).putInt("userId",p.userId).putBoolean("complete",true).apply();
    }
    public String device(){return preferences.getString("device",null);}
    public void rememberDevice(String address){preferences.edit().putString("device",address).apply();}
}
