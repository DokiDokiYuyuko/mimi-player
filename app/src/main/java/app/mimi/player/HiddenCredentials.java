package app.mimi.player;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.json.JSONObject;

/** Durable PIN verifier. Activity authorization is intentionally process-local. */
final class HiddenCredentials {
    private static final String PIN_KEY = "mimi.hidden.pin.v1";
    private static final int ITERATIONS = 210_000;
    private static final int MAX_ATTEMPTS = 5;
    static final long COOLDOWN_MS = 30_000;

    private static HiddenCredentials instance;

    static synchronized HiddenCredentials get(Context context) {
        if (instance == null) instance = new HiddenCredentials(context.getApplicationContext());
        return instance;
    }

    private final AtomicFile file;
    private final String keyAlias;
    private final int bootCount;

    private HiddenCredentials(Context context) {
        this(context, new File(context.getFilesDir(), "hidden-credentials.json"), PIN_KEY);
    }

    HiddenCredentials(Context context, File path, String keyAlias) {
        file = new AtomicFile(path);
        this.keyAlias = keyAlias;
        bootCount = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
    }

    private JSONObject read() throws Exception {
        try {
            return new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
        } catch (FileNotFoundException missing) {
            return new JSONObject();
        }
    }

    synchronized boolean configured() {
        try {
            return read().has("verifier");
        } catch (Exception invalid) {
            return true;
        }
    }

    synchronized boolean biometricEnabled() {
        try {
            return read().optBoolean("biometric");
        } catch (Exception invalid) {
            return false;
        }
    }

    synchronized void biometricEnabled(boolean value) throws Exception {
        JSONObject data = read();
        if (!data.has("verifier")) throw new IOException("请先设置密码");
        LibraryStore.put(data, "biometric", value);
        save(data);
    }

    synchronized void setPin(String pin, String confirmation, String previous) throws Exception {
        if (!pin.matches("[0-9]{6}")) throw new IOException("请输入 6 位数字密码");
        if (!pin.equals(confirmation)) throw new IOException("两次输入的密码不一致");
        JSONObject data = read();
        boolean existing = data.has("verifier");
        if (existing && previous == null) throw new IOException("请先验证原密码");
        if (existing) checkPin(previous, data);
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        LibraryStore.put(data, "salt", encode(salt));
        LibraryStore.put(data, "verifier", encode(derive(pin, salt, !existing)));
        clearAttempts(data);
        save(data);
    }

    synchronized void verify(String pin) throws Exception {
        JSONObject data = read();
        checkPin(pin, data);
        clearAttempts(data);
        save(data);
    }

    private void checkPin(String pin, JSONObject data) throws Exception {
        if (!data.has("verifier")) throw new IOException("请先设置密码");
        long seconds = (remaining(data) + 999) / 1000;
        if (seconds > 0) throw new IOException("请在 " + seconds + " 秒后重试");
        if (!pin.matches("[0-9]{6}")) throw new IOException("请输入 6 位数字密码");
        if (MessageDigest.isEqual(decode(data.getString("verifier")), derive(pin, decode(data.getString("salt")), false))) return;
        int attempts = data.optInt("attempts") + 1;
        if (attempts >= MAX_ATTEMPTS) {
            LibraryStore.put(data, "attempts", 0);
            LibraryStore.put(data, "until", System.currentTimeMillis() + COOLDOWN_MS);
            LibraryStore.put(data, "elapsedStart", SystemClock.elapsedRealtime());
            LibraryStore.put(data, "bootCount", bootCount);
            save(data);
            throw new IOException("密码错误次数过多，请在 30 秒后重试");
        }
        LibraryStore.put(data, "attempts", attempts);
        save(data);
        throw new IOException("密码不正确");
    }

    private long remaining(JSONObject data) {
        if (!data.has("until")) return 0;
        long since = SystemClock.elapsedRealtime() - data.optLong("elapsedStart");
        if (bootCount >= 0 && data.optInt("bootCount", -1) == bootCount && since >= 0) {
            return Math.max(0, COOLDOWN_MS - since);
        }
        return Math.max(0, Math.min(COOLDOWN_MS, data.optLong("until") - System.currentTimeMillis()));
    }

    private void clearAttempts(JSONObject data) {
        data.remove("attempts");
        data.remove("until");
        data.remove("elapsedStart");
        data.remove("bootCount");
    }

    private byte[] derive(String pin, byte[] salt, boolean create) throws Exception {
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
        keys.load(null);
        if (!keys.containsAlias(keyAlias)) {
            if (!create) throw new IOException("无法读取密码验证信息");
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_SIGN)
                    .setDigests(KeyProperties.DIGEST_SHA256).build());
            generator.generateKey();
        }
        char[] digits = pin.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(digits, salt, ITERATIONS, 256);
        Arrays.fill(digits, '\0');
        byte[] derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init((SecretKey) keys.getKey(keyAlias, null));
            return mac.doFinal(derived);
        } finally {
            spec.clearPassword();
            Arrays.fill(derived, (byte) 0);
        }
    }

    private void save(JSONObject data) throws IOException {
        FileOutputStream output = file.startWrite();
        try {
            output.write(data.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (IOException failure) {
            file.failWrite(output);
            throw failure;
        }
    }

    private static String encode(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    private static byte[] decode(String value) {
        return Base64.decode(value, Base64.NO_WRAP);
    }
}
