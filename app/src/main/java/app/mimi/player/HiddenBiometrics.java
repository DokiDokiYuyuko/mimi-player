package app.mimi.player;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import androidx.annotation.RequiresApi;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

@RequiresApi(30)
final class HiddenBiometrics {
    private static final String KEY = "mimi.hidden.biometric.v1";

    static boolean available(Context context) {
        BiometricManager manager = context.getSystemService(BiometricManager.class);
        return manager != null && context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
                && manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS;
    }

    interface Outcome {
        void complete(boolean success);
    }

    static void prompt(Activity activity, boolean enabling, CancellationSignal cancel, Outcome outcome) throws Exception {
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
        keys.load(null);
        if (enabling) {
            keys.deleteEntry(KEY);
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                    .setInvalidatedByBiometricEnrollment(true).build());
            generator.generateKey();
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, (SecretKey) keys.getKey(KEY, null));
        BiometricPrompt prompt = new BiometricPrompt.Builder(activity)
                .setTitle(enabling ? "启用指纹快捷解锁" : "解锁隐藏空间")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButton("返回", activity.getMainExecutor(), (dialog, which) -> outcome.complete(false))
                .build();
        prompt.authenticate(new BiometricPrompt.CryptoObject(cipher), cancel, activity.getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
            @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                boolean success = false;
                try {
                    Cipher authenticated = result.getCryptoObject().getCipher();
                    authenticated.doFinal(new byte[16]);
                    success = true;
                } catch (Exception ignored) {
                    // An authentication callback alone cannot authorize access.
                }
                outcome.complete(success);
            }

            @Override public void onAuthenticationError(int error, CharSequence message) {
                outcome.complete(false);
            }
        });
    }

    private HiddenBiometrics() {}
}
