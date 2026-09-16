package com.simplerag.adapter.out.security;

import com.simplerag.application.diagnostics.DiagnosticSink;
import com.simplerag.application.port.out.SecretStore;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Stores the API key as a Generic Credential for the current Windows user. */
public final class WindowsCredentialManagerSecretStore implements SecretStore {
    private static final int CRED_TYPE_GENERIC = 1;
    private static final int CRED_PERSIST_LOCAL_MACHINE = 2;
    private static final int ERROR_NOT_FOUND = 1168;
    private static final String MARKER = "wincred:v1:SimpleRAG/API";
    private static final String TARGET = "SimpleRAG/API";
    private final SecretStore fallback;
    private final DiagnosticSink diagnostics;

    public WindowsCredentialManagerSecretStore(SecretStore fallback, DiagnosticSink diagnostics) {
        this.fallback = fallback;
        this.diagnostics = diagnostics == null ? DiagnosticSink.noop() : diagnostics;
    }

    @Override
    public String encrypt(String plainText) {
        return encrypt("chat", plainText);
    }

    @Override
    public String encrypt(String namespace, String plainText) {
        String target = target(namespace);
        if (plainText == null || plainText.isBlank()) {
            deleteCredential(target);
            return "";
        }
        if (!isWindows()) return fallback.encrypt(plainText);
        try {
            byte[] bytes = plainText.getBytes(StandardCharsets.UTF_16LE);
            Credential credential = new Credential();
            credential.Type = CRED_TYPE_GENERIC;
            credential.TargetName = new WString(target);
            credential.UserName = new WString(System.getProperty("user.name", "SimpleRAG"));
            credential.Persist = CRED_PERSIST_LOCAL_MACHINE;
            credential.CredentialBlobSize = bytes.length;
            credential.CredentialBlob = new Memory(bytes.length);
            credential.CredentialBlob.write(0, bytes, 0, bytes.length);
            credential.write();
            if (!CredentialsApi.INSTANCE.CredWriteW(credential, 0)) {
                throw new IllegalStateException("CredWriteW failed with Windows error " + Native.getLastError());
            }
            diagnostics.record("credential stored", "security", "API credential stored in Windows Credential Manager");
            return marker(target);
        } catch (RuntimeException failure) {
            diagnostics.record("credential store failed", "security", failure.getClass().getSimpleName(),
                    Map.of("backend", "windows-credential-manager"));
            throw new IllegalStateException("无法写入 Windows 凭据管理器，API Key 未保存", failure);
        }
    }

    @Override
    public String decrypt(String encoded) {
        return decrypt("chat", encoded);
    }

    @Override
    public String decrypt(String namespace, String encoded) {
        if (encoded == null || encoded.isBlank()) return "";
        String target = target(namespace);
        // The original chat credential used this fixed marker and target; keep it readable.
        if (MARKER.equals(encoded)) target = TARGET;
        else if (!marker(target).equals(encoded)) return fallback.decrypt(encoded);
        if (!isWindows()) {
            throw new IllegalStateException("该 API Key 保存在 Windows 凭据管理器，当前系统无法读取");
        }
        PointerByReference reference = new PointerByReference();
        if (!CredentialsApi.INSTANCE.CredReadW(new WString(target), CRED_TYPE_GENERIC, 0, reference)) {
            int error = Native.getLastError();
            if (error == ERROR_NOT_FOUND) {
                throw new IllegalStateException("数据库记录了 API Key，但 Windows 凭据管理器中已不存在");
            }
            throw new IllegalStateException("无法读取 Windows 凭据管理器，错误码 " + error);
        }
        Pointer pointer = reference.getValue();
        Credential credential = new Credential(pointer);
        try {
            credential.read();
            byte[] bytes = credential.CredentialBlob.getByteArray(0, credential.CredentialBlobSize);
            return new String(bytes, StandardCharsets.UTF_16LE);
        } finally {
            CredentialsApi.INSTANCE.CredFree(pointer);
        }
    }

    private void deleteCredential(String target) {
        if (!isWindows()) return;
        if (!CredentialsApi.INSTANCE.CredDeleteW(new WString(target), CRED_TYPE_GENERIC, 0)) {
            int error = Native.getLastError();
            if (error != ERROR_NOT_FOUND) {
                throw new IllegalStateException("无法从 Windows 凭据管理器删除 API Key，错误码 " + error);
            }
        }
    }

    private static String target(String namespace) {
        String value = namespace == null ? "" : namespace.strip().toLowerCase(java.util.Locale.ROOT);
        return value.isEmpty() || "chat".equals(value) ? TARGET : TARGET + "/" + value;
    }

    private static String marker(String target) {
        return "wincred:v1:" + target;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("windows");
    }

    private interface CredentialsApi extends StdCallLibrary {
        CredentialsApi INSTANCE = Native.load("Advapi32", CredentialsApi.class);
        boolean CredWriteW(Credential credential, int flags);
        boolean CredReadW(WString targetName, int type, int flags, PointerByReference credential);
        boolean CredDeleteW(WString targetName, int type, int flags);
        void CredFree(Pointer credential);
    }

    @Structure.FieldOrder({"dwLowDateTime", "dwHighDateTime"})
    public static final class FileTime extends Structure {
        public int dwLowDateTime;
        public int dwHighDateTime;
    }

    @Structure.FieldOrder({"Flags", "Type", "TargetName", "Comment", "LastWritten",
            "CredentialBlobSize", "CredentialBlob", "Persist", "AttributeCount", "Attributes",
            "TargetAlias", "UserName"})
    public static final class Credential extends Structure {
        public int Flags;
        public int Type;
        public WString TargetName;
        public WString Comment;
        public FileTime LastWritten = new FileTime();
        public int CredentialBlobSize;
        public Pointer CredentialBlob;
        public int Persist;
        public int AttributeCount;
        public Pointer Attributes;
        public WString TargetAlias;
        public WString UserName;

        public Credential() { }
        public Credential(Pointer pointer) { super(pointer); read(); }
    }
}
