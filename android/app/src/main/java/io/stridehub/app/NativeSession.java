package io.stridehub.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Instant;
import java.time.OffsetDateTime;
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
        return current != null && current.expiresAt.isAfter(Instant.now()) ? current.token : "";
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
        ServerAddress server = ServerAddress.parse(address, true);
        Instant expiry = OffsetDateTime.parse(expiresAt).toInstant();
        if (token == null || token.isEmpty() || token.indexOf('\n') >= 0 || token.indexOf('\r') >= 0
                || username == null || username.isEmpty() || !expiry.isAfter(Instant.now())) {
            throw new IllegalArgumentException("服务器返回的登录凭据无效，请重新登录。");
        }
        // Prepare encryption first. A Keystore failure must not leave a half-switched account.
        String sealed = "";
        if (remember) {
            try { sealed = encrypt(token, server.baseUrl(), username); }
            catch (Exception error) { throw new IllegalStateException("无法安全保存登录，请关闭保持登录后重试。", error); }
        }
        SharedPreferences connection = context.getSharedPreferences("connection", Context.MODE_PRIVATE);
        generation++;
        if (!server.baseUrl().equals(connection.getString("server", ""))
                || !username.equals(connection.getString("username", ""))) {
            io.stridehub.app.sync.NasSync.disconnect(context);
        }
        SharedPreferences.Editor persisted = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear();
        if (remember) persisted.putString("server", server.baseUrl()).putString("username", username)
                .putString("token", sealed).putString("expiresAt", expiresAt);
        if (!persisted.commit()) throw new IllegalStateException("无法保存登录状态，请重试。");
        if (!connection.edit().putString("server", server.baseUrl()).putString("username", username)
                .putBoolean("http", server.isHttp()).putBoolean("remember", remember).commit()) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit();
            current = null;
            loaded = true;
            throw new IllegalStateException("无法保存服务器地址，请重试。");
        }
        current = new Session(server.baseUrl(), username, token, expiry);
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
            Instant expiry = OffsetDateTime.parse(prefs.getString("expiresAt", "")).toInstant();
            if (expiry.isAfter(Instant.now())) current = new Session(server, username,
                    decrypt(prefs.getString("token", ""), server, username), expiry);
        } catch (Exception unusable) {
            // Keystore invalidation and damaged preferences require native sign-in, never a web fallback.
            prefs.edit().clear().commit();
        }
    }

    private static boolean matchesConnection(Context context, String server, String username) {
        SharedPreferences connection = context.getSharedPreferences("connection", Context.MODE_PRIVATE);
        return server.equals(connection.getString("server", "")) && username.equals(connection.getString("username", ""));
    }

    private static SecretKey key() throws Exception {
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

    private static String encrypt(String token, String server, String username) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        cipher.updateAAD((server + "\n" + username).getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                + Base64.encodeToString(cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }

    private static String decrypt(String sealed, String server, String username) throws Exception {
        String[] parts = sealed.split(":", -1);
        if (parts.length != 2) throw new IllegalArgumentException("Invalid credential envelope");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        cipher.updateAAD((server + "\n" + username).getBytes(StandardCharsets.UTF_8));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    private static final class Session {
        final String server, username, token;
        final Instant expiresAt;
        Session(String server, String username, String token, Instant expiresAt) {
            this.server = server; this.username = username; this.token = token; this.expiresAt = expiresAt;
        }
    }
}
