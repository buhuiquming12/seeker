package com.simplerag.adapter.out.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.simplerag.common.crypto.Digests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretCodecTest {
    @TempDir Path temp;

    @Test
    void ciphertextUsesARandomExternalKeyAndSurvivesRestart() throws Exception {
        Path key = temp.resolve("secret.key");
        SecretCodec first = new SecretCodec(key);

        String encrypted = first.encrypt("course-secret");

        assertTrue(encrypted.startsWith("local:v2:"));
        assertTrue(Files.isRegularFile(key));
        assertEquals("course-secret", new SecretCodec(key).decrypt(encrypted));
        assertNotEquals(encrypted, first.encrypt("course-secret"), "AES-GCM IV must remain random");
        assertThrows(IllegalStateException.class,
                () -> new SecretCodec(temp.resolve("other.key")).decrypt(encrypted));
    }

    @Test
    void corruptCiphertextFailsLoudlyInsteadOfLookingLikeAnUnsetKey() {
        assertThrows(IllegalStateException.class,
                () -> new SecretCodec(temp.resolve("secret.key")).decrypt("local:v2:not-base64"));
    }

    @Test
    void legacyCiphertextRemainsReadableDuringMigration() throws Exception {
        String legacy = legacyEncrypt("old-secret");

        assertEquals("old-secret", new SecretCodec(temp.resolve("secret.key")).decrypt(legacy));
        assertTrue(Files.notExists(temp.resolve("secret.key")),
                "reading legacy data must not create the v2 key until a new value is saved");
    }

    private static String legacyEncrypt(String plaintext) throws Exception {
        String material = System.getProperty("user.name", "") + "|"
                + System.getProperty("user.home", "") + "|SimpleRAG-local-secret-v1";
        SecretKeySpec key = new SecretKeySpec(Arrays.copyOf(Digests.sha256Utf8(material), 16), "AES");
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] payload = Arrays.copyOf(iv, iv.length + encrypted.length);
        System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
        return Base64.getEncoder().encodeToString(payload);
    }
}
