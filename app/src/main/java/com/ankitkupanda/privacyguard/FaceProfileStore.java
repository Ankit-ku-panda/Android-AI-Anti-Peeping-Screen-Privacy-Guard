package com.ankitkupanda.privacyguard;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypts the owner's numeric face signatures with an Android Keystore key. */
public final class FaceProfileStore {
    private static final String KEY_ALIAS = "privacyguard_owner_profile_key_v2";
    private static final String PROFILE_KEY = "encrypted_owner_profile_v2";
    private static final String LEGACY_KEY_ALIAS = "privacyguard_owner_profile_key";
    private static final String LEGACY_PROFILE_KEY = "encrypted_owner_profile_v1";
    private static final int FORMAT_MAGIC = 0x50474132;

    private final SharedPreferences preferences;

    public FaceProfileStore(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(AppConfig.PREFS, Context.MODE_PRIVATE);
        removeIncompatibleLegacyProfile();
    }

    public boolean hasProfile() {
        return preferences.contains(PROFILE_KEY) && !load().isEmpty();
    }

    public void save(List<float[]> templates) throws Exception {
        if (templates == null || templates.isEmpty()) {
            throw new IllegalArgumentException("At least one template is required");
        }
        int count = templates.size();
        ByteBuffer plain = ByteBuffer.allocate(12 + count * FaceSignature.DIMENSION * 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        plain.putInt(FORMAT_MAGIC);
        plain.putInt(count);
        plain.putInt(FaceSignature.DIMENSION);
        for (float[] template : templates) {
            if (template == null || template.length != FaceSignature.DIMENSION) {
                throw new IllegalArgumentException("Invalid face signature dimension");
            }
            for (float value : template) {
                plain.putFloat(value);
            }
        }

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] encrypted = cipher.doFinal(plain.array());
        byte[] iv = cipher.getIV();
        ByteBuffer stored = ByteBuffer.allocate(4 + iv.length + encrypted.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        stored.putInt(iv.length);
        stored.put(iv);
        stored.put(encrypted);
        boolean saved = preferences.edit().putString(PROFILE_KEY,
                Base64.encodeToString(stored.array(), Base64.NO_WRAP)).commit();
        if (!saved) {
            throw new IllegalStateException("Encrypted owner profile could not be written");
        }
    }

    public List<float[]> load() {
        String encoded = preferences.getString(PROFILE_KEY, null);
        if (encoded == null || encoded.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            ByteBuffer stored = ByteBuffer.wrap(Base64.decode(encoded, Base64.NO_WRAP))
                    .order(ByteOrder.LITTLE_ENDIAN);
            int ivLength = stored.getInt();
            if (ivLength < 12 || ivLength > 32 || stored.remaining() <= ivLength) {
                return Collections.emptyList();
            }
            byte[] iv = new byte[ivLength];
            stored.get(iv);
            byte[] encrypted = new byte[stored.remaining()];
            stored.get(encrypted);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            ByteBuffer plain = ByteBuffer.wrap(cipher.doFinal(encrypted))
                    .order(ByteOrder.LITTLE_ENDIAN);
            if (plain.getInt() != FORMAT_MAGIC) {
                return Collections.emptyList();
            }
            int count = plain.getInt();
            int dimension = plain.getInt();
            if (count < 1 || count > 32 || dimension != FaceSignature.DIMENSION
                    || plain.remaining() != count * dimension * 4) {
                return Collections.emptyList();
            }
            List<float[]> templates = new ArrayList<>(count);
            for (int item = 0; item < count; item++) {
                float[] template = new float[dimension];
                for (int i = 0; i < dimension; i++) {
                    template[i] = plain.getFloat();
                }
                templates.add(template);
            }
            return templates;
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    public void clear() {
        preferences.edit()
                .remove(PROFILE_KEY)
                .remove(LEGACY_PROFILE_KEY)
                .commit();
        deleteKey(KEY_ALIAS);
        deleteKey(LEGACY_KEY_ALIAS);
    }

    private void removeIncompatibleLegacyProfile() {
        if (!preferences.contains(LEGACY_PROFILE_KEY)) {
            return;
        }
        preferences.edit().remove(LEGACY_PROFILE_KEY).apply();
        deleteKey(LEGACY_KEY_ALIAS);
    }

    private void deleteKey(String alias) {
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (store.containsAlias(alias)) {
                store.deleteEntry(alias);
            }
        } catch (Exception ignored) {
            // The encrypted profile is already removed, so a stale key is harmless.
        }
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        java.security.Key existing = store.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) {
            return (SecretKey) existing;
        }

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec specification = new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build();
        generator.init(specification);
        return generator.generateKey();
    }
}
