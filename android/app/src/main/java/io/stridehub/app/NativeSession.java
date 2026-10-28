package io.stridehub.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Instant;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Native credentials are independent of the durable weighing device credentials and database. */
public final class NativeSession {
    private static final String PREFERENCES = "native_session";
    private static final String KEY = "stridehub-mobile-session-v1";
    private static Session current;
    private static boolean loaded;
    private static volatile long generation;

    private NativeSession() { }

    public static long generation() { return generation; }

    public static synchronized String token(Context context) {
        load(context);
        return current != null && current.lifetime.isUsableAt(Instant.now()) ? current.token : "";
    }

    /** An expired legacy credential may ONLY be sent to the authoritative upgrade endpoint. */
    public static synchronized String candidateToken(Context context) {
        load(context);
        return current!=null ? current.token : "";
    }
    public static synchronized boolean isPersistent(Context context) {
        load(context);
        return current!=null && current.lifetime.persistent();
    }
    public static synchronized boolean promotePersistent(Context context,String token,long expectedGeneration) {
        load(context);
        if(generation!=expectedGeneration || current==null || !current.token.equals(token)
                || !matchesConnection(context,current.server,current.username))return false;
        if(current.lifetime.persistent())return true;
        SharedPreferences prefs=context.getSharedPreferences(PREFERENCES,Context.MODE_PRIVATE);
        // Keep an existing encrypted token byte-for-byte; only session metadata changes.
        String sealed=prefs.getString("token","");
        if(!current.server.equals(prefs.getString("server","")) || !current.username.equals(prefs.getString("username","")) || sealed.isEmpty()) {
            try{sealed=encrypt(token,current.server,current.username);}
            catch(Exception error){throw new IllegalStateException("无法安全保存持久登录，请重试。",error);}
        }
        if(!prefs.edit().putString("server",current.server).putString("username",current.username)
                .putString("token",sealed).putBoolean("persistent",true).remove("expiresAt").commit())
            throw new IllegalStateException("无法保存持久登录，请重试。");
        if(!context.getSharedPreferences("connection",Context.MODE_PRIVATE).edit().putBoolean("remember",true).commit())
            throw new IllegalStateException("无法保存持久登录，请重试。");
        current=new Session(current.server,current.username,token,SessionLifetime.from(true,null));
        return true;
    }
    public static synchronized void rejectIfCurrent(Context context,String token,long expectedGeneration) {
        load(context);
        if(generation==expectedGeneration && current!=null && current.token.equals(token))clear(context);
    }

    public static boolean signedIn(Context context) { return !token(context).isEmpty(); }

    public static ServerAddress server(Context context) {
        SharedPreferences preferences = context.getSharedPreferences("connection", Context.MODE_PRIVATE);
        try { return ServerAddress.parse(preferences.getString("server", ""), preferences.getBoolean("http", false)); }
        catch (IllegalArgumentException invalid) { return null; }
    }

    public static String username(Context context) {
        return context.getSharedPreferences("connection", Context.MODE_PRIVATE).getString("username", "");
    }

    /** Called only after the native connection form validated the explicit HTTP choice. */
    public static synchronized void save(Context context, String address, String username, String token,
                                         boolean remember, String expiresAt) {
        save(context,address,username,token,remember,expiresAt,false);
    }

    public static synchronized void save(Context context, String address, String username, String token,
                                         boolean remember, String expiresAt, boolean persistent) {
        ServerAddress server = ServerAddress.parse(address, true);
        SessionLifetime lifetime=SessionLifetime.from(persistent,expiresAt);
        remember=remember || persistent;
        if (token == null || token.isEmpty() || token.indexOf('\n') >= 0 || token.indexOf('\r') >= 0
                || username == null || username.isEmpty() || !lifetime.isUsableAt(Instant.now())) {
            throw new IllegalArgumentException("服务器返回的登录凭据无效，请重新登录。");
        }
        // Prepare encryption first. A Keystore failure must not leave a half-switched account.
        String sealed = "";
        if (remember) {
            try { sealed = encrypt(token, server.baseUrl(), username); }
            catch (Exception error) { throw new IllegalStateException("无法安全保存登录，请检查设备安全存储后重试。", error); }
        }
        SharedPreferences connection = context.getSharedPreferences("connection", Context.MODE_PRIVATE);
        generation++;
        if (!server.baseUrl().equals(connection.getString("server", ""))
                || !username.equals(connection.getString("username", ""))) {
            io.stridehub.app.sync.NasSync.disconnect(context);
        }
        SharedPreferences.Editor persisted = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear();
        if (remember) {
            persisted.putString("server", server.baseUrl()).putString("username", username)
                .putString("token", sealed).putBoolean("persistent",persistent);
            if(!persistent)persisted.putString("expiresAt",expiresAt);
        }
        if (!persisted.commit()) throw new IllegalStateException("无法保存登录状态，请重试。");
        if (!connection.edit().putString("server", server.baseUrl()).putString("username", username)
                .putBoolean("http", server.isHttp()).putBoolean("remember", remember).commit()) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit();
            current = null;
            loaded = true;
            throw new IllegalStateException("无法保存服务器地址，请重试。");
        }
        current = new Session(server.baseUrl(), username, token, lifetime);
        loaded = true;
    }

    /** Clear only the mobile credential; retain connection hints and all local weighing records. */
    public static synchronized void clear(Context context) {
        generation++;
        current = null;
        loaded = true;
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit();
        io.stridehub.app.sync.NasSync.disconnect(context);
    }

    private static void load(Context context) {
        if (loaded) {
            if (current != null && !matchesConnection(context, current.server, current.username)) current = null;
            return;
        }
        loaded = true;
        SharedPreferences prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        String server = prefs.getString("server", ""), username = prefs.getString("username", "");
        if (!matchesConnection(context, server, username) || server.isEmpty() || username.isEmpty()) return;
        try {
            boolean persistent=prefs.contains("persistent") && prefs.getBoolean("persistent",false);
            SessionLifetime lifetime=SessionLifetime.from(persistent,prefs.getString("expiresAt",null));
            String token=decrypt(prefs.getString("token", ""),server,username);
            if(token.isEmpty() || token.indexOf('\n')>=0 || token.indexOf('\r')>=0)throw new IllegalArgumentException("Invalid credential");
            if(NativeRevocations.wasLoggedOut(context,server,username,token)) {
                prefs.edit().clear().commit();
                return;
            }
            current=new Session(server,username,token,lifetime);
        } catch (Exception unusable) {
            // Keystore invalidation and damaged preferences require native sign-in, never a web fallback.
            prefs.edit().clear().commit();
        }
    }

    private static boolean matchesConnection(Context context, String server, String username) {
        SharedPreferences connection = context.getSharedPreferences("connection", Context.MODE_PRIVATE);
        return server.equals(connection.getString("server", "")) && username.equals(connection.getString("username", ""));
    }

    private static synchronized SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(KEY)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(KEY, null);
    }

    static String encrypt(String token, String server, String username) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        cipher.updateAAD((server + "\n" + username).getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                + Base64.encodeToString(cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }

    static String decrypt(String sealed, String server, String username) throws Exception {
        String[] parts = sealed.split(":", -1);
        if (parts.length != 2) throw new IllegalArgumentException("Invalid credential envelope");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        cipher.updateAAD((server + "\n" + username).getBytes(StandardCharsets.UTF_8));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    private static final class Session {
        final String server, username, token;
        final SessionLifetime lifetime;
        Session(String server, String username, String token, SessionLifetime lifetime) {
            this.server = server; this.username = username; this.token = token; this.lifetime = lifetime;
        }
    }
}
