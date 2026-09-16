package com.simplerag.adapter.out.security;

import com.simplerag.common.crypto.Digests;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.nio.file.attribute.PosixFilePermission;

public final class SecretCodec implements com.simplerag.application.port.out.SecretStore {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PREFIX = "local:v2:";
    private final Path keyPath;
    private volatile SecretKeySpec localKey;

    public SecretCodec() {
        this(defaultKeyPath());
    }

    public SecretCodec(Path keyPath) {
        this.keyPath = keyPath.toAbsolutePath().normalize();
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return "";
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, localKey(), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (Exception failure) {
            throw new IllegalStateException("无法加密 API Key", failure);
        }
    }

    public String decrypt(String encoded) {
        if (encoded == null || encoded.isBlank()) return "";
        try {
            boolean current = encoded.startsWith(PREFIX);
            String value = current ? encoded.substring(PREFIX.length()) : encoded;
            byte[] payload = Base64.getDecoder().decode(value);
            if (payload.length < 13) throw new IllegalArgumentException("encrypted payload is too short");
            byte[] iv = Arrays.copyOfRange(payload, 0, 12);
            byte[] encrypted = Arrays.copyOfRange(payload, 12, payload.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, current ? localKey() : legacyKey(),
                    new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception failure) {
            throw new IllegalStateException("无法解密 API Key", failure);
        }
    }

    private SecretKeySpec localKey() throws Exception {
        SecretKeySpec existing = localKey;
        if (existing != null) return existing;
        synchronized (this) {
            if (localKey != null) return localKey;
            Files.createDirectories(keyPath.getParent());
            byte[] bytes;
            if (Files.exists(keyPath)) {
                bytes = decodeKey(Files.readString(keyPath, StandardCharsets.US_ASCII));
            } else {
                byte[] generated = new byte[32];
                RANDOM.nextBytes(generated);
                try {
                    Files.writeString(keyPath, Base64.getEncoder().encodeToString(generated),
                            StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE);
                    restrictPermissions(keyPath);
                    bytes = generated;
                } catch (FileAlreadyExistsException raced) {
                    bytes = decodeKey(Files.readString(keyPath, StandardCharsets.US_ASCII));
                }
            }
            localKey = new SecretKeySpec(bytes, "AES");
            return localKey;
        }
    }

    private static byte[] decodeKey(String encoded) {
        byte[] key = Base64.getDecoder().decode(encoded.strip());
        if (key.length != 32) throw new IllegalStateException("本地凭据密钥格式无效");
        return key;
    }

    private static void restrictPermissions(Path path) throws Exception {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Windows uses Credential Manager in production; POSIX permissions are unavailable there.
        }
    }

    private static Path defaultKeyPath() {
        String configured = System.getProperty("simplerag.secret.key.path", "").strip();
        if (!configured.isEmpty()) return Path.of(configured);
        return Path.of(System.getProperty("user.home"), ".simplerag", "secret.key");
    }

    /** Reads ciphertext written before v2 so an upgrade does not erase saved settings. */
    private static SecretKeySpec legacyKey() throws Exception {
        String material = System.getProperty("user.name", "") + "|"
                + System.getProperty("user.home", "") + "|SimpleRAG-local-secret-v1";
        byte[] digest = Digests.sha256Utf8(material);
        return new SecretKeySpec(Arrays.copyOf(digest, 16), "AES");
    }
}
