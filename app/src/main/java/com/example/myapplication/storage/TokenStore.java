package com.example.myapplication.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import com.example.myapplication.auth.AuthToken;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class TokenStore {

    private static final String KEY_ALIAS = "yandex_music_auth_token";
    private static final String PREFERENCES = "secure_auth";
    private static final String ENCRYPTED_TOKEN = "encrypted_token";
    private static final String TOKEN_IV = "token_iv";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int CURRENT_SCOPE_VERSION = 1;

    private final SharedPreferences preferences;

    public TokenStore(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public void save(AuthToken token) throws Exception {
        JSONObject json = new JSONObject();
        json.put("accessToken", token.getAccessToken());
        json.put("refreshToken", token.getRefreshToken());
        json.put("expiresAt", token.getExpiresAtEpochSeconds());
        json.put("scopeVersion", CURRENT_SCOPE_VERSION);

        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] encrypted = cipher.doFinal(json.toString().getBytes(StandardCharsets.UTF_8));

        preferences.edit()
                .putString(ENCRYPTED_TOKEN, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(TOKEN_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .apply();
    }

    public AuthToken load() {
        String encryptedToken = preferences.getString(ENCRYPTED_TOKEN, null);
        String encodedIv = preferences.getString(TOKEN_IV, null);
        if (encryptedToken == null || encodedIv == null) {
            return null;
        }

        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    getOrCreateKey(),
                    new GCMParameterSpec(128, Base64.decode(encodedIv, Base64.NO_WRAP))
            );
            byte[] decrypted = cipher.doFinal(Base64.decode(encryptedToken, Base64.NO_WRAP));
            JSONObject json = new JSONObject(new String(decrypted, StandardCharsets.UTF_8));
            if (json.optInt("scopeVersion", 0) != CURRENT_SCOPE_VERSION) {
                clear();
                return null;
            }
            return new AuthToken(
                    json.getString("accessToken"),
                    json.optString("refreshToken", null),
                    json.getLong("expiresAt")
            );
        } catch (Exception error) {
            clear();
            return null;
        }
    }

    public void clear() {
        preferences.edit().clear().apply();
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);

        KeyStore.Entry existingEntry = keyStore.getEntry(KEY_ALIAS, null);
        if (existingEntry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) existingEntry).getSecretKey();
        }

        KeyGenerator keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
        );
        keyGenerator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return keyGenerator.generateKey();
    }
}
