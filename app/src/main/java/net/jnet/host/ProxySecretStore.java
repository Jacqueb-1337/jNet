package net.jnet.host;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Keep the optional SOCKS password encrypted at rest with an Android Keystore key. */
final class ProxySecretStore {
    private static final String STORE = "jnet_secure_proxy";
    private static final String NAME = "encrypted_proxy_password";
    private static final String ALIAS = "net.jnet.host.socks5.pass.v1";
    private ProxySecretStore() {}

    static String read(Context context) throws Exception {
        String value = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
                .getString(NAME, null);
        if (value == null) return "";
        byte[] encrypted = Base64.decode(value, Base64.NO_WRAP);
        if (encrypted.length <= 12) throw new GeneralSecurityException("Invalid encrypted proxy password");
        SecretKey secret = getOrCreateKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, secret,
                new GCMParameterSpec(128, Arrays.copyOfRange(encrypted, 0, 12)));
        return new String(cipher.doFinal(encrypted, 12, encrypted.length - 12),
                StandardCharsets.UTF_8);
    }

    static void write(Context context, String password) throws Exception {
        if (password.getBytes(StandardCharsets.UTF_8).length > 255)
            throw new IllegalArgumentException("SOCKS5 password exceeds 255 bytes");
        if (password.isEmpty()) {
            if (!context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
                    .edit().remove(NAME).commit())
                throw new GeneralSecurityException("Unable to clear proxy password");
            return;
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] iv = cipher.getIV();
        byte[] data = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[iv.length + data.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(data, 0, combined, iv.length, data.length);
        String encoded = Base64.encodeToString(combined, Base64.NO_WRAP);
        if (!context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
                .putString(NAME, encoded).commit())
            throw new GeneralSecurityException("Unable to persist proxy password");
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        var existing = store.getKey(ALIAS, null);
        if (existing instanceof SecretKey key) return key;
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
