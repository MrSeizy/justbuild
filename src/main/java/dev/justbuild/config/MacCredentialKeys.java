package dev.justbuild.config;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.platform.mac.CoreFoundation;
import com.sun.jna.platform.mac.CoreFoundation.CFDataRef;
import com.sun.jna.platform.mac.CoreFoundation.CFIndex;
import com.sun.jna.platform.mac.CoreFoundation.CFMutableDictionaryRef;
import com.sun.jna.platform.mac.CoreFoundation.CFStringRef;
import com.sun.jna.platform.mac.CoreFoundation.CFTypeRef;
import com.sun.jna.ptr.PointerByReference;
import java.io.IOException;

/** Native Keychain calls keep key material out of subprocess arguments, output, and temporary files. */
final class MacCredentialKeys {
    private static final String SERVICE = "dev.justbuild.config-encryption";
    private static final CoreFoundation CF = CoreFoundation.INSTANCE;
    private static final NativeLibrary LIBRARY = NativeLibrary.getInstance("Security");
    private static final Security SECURITY = Native.load("Security", Security.class);

    interface Security extends Library {
        int SecItemAdd(CFMutableDictionaryRef attributes, PointerByReference result);
        int SecItemCopyMatching(CFMutableDictionaryRef query, PointerByReference result);
        int SecItemDelete(CFMutableDictionaryRef query);
    }

    private MacCredentialKeys() { }

    static void create(String id, byte[] key) throws IOException {
        try (Query query = new Query(id); Memory bytes = new Memory(key.length)) {
            bytes.write(0, key, 0, key.length);
            CFDataRef data = CF.CFDataCreate(null, bytes, new CFIndex(key.length));
            try {
                query.value.setValue(constant("kSecValueData"), data);
                int status = SECURITY.SecItemAdd(query.value, null);
                if (status != 0) throw new IOException("Keychain write failed (OSStatus " + status + ")");
            } finally { data.release(); bytes.clear(); }
        }
    }

    static byte[] load(String id) throws IOException {
        try (Query query = new Query(id)) {
            CFTypeRef yes = new CFTypeRef(NativeLibrary.getInstance("CoreFoundation")
                    .getGlobalVariableAddress("kCFBooleanTrue").getPointer(0));
            query.value.setValue(constant("kSecReturnData"), yes);
            var result = new PointerByReference();
            int status = SECURITY.SecItemCopyMatching(query.value, result);
            if (status != 0 || result.getValue() == null) throw new IOException("Keychain read failed (OSStatus " + status + ")");
            CFDataRef data = new CFDataRef(result.getValue());
            try {
                if (data.getLength() != 32) throw new IOException("Invalid key length");
                return data.getBytePtr().getByteArray(0, data.getLength());
            } finally { data.release(); }
        }
    }

    /** Used only by the opt-in synthetic vault test, to remove its own randomly named item. */
    static void delete(String id) throws IOException {
        try (Query query = new Query(id)) {
            if (SECURITY.SecItemDelete(query.value) != 0) throw new IOException("Keychain cleanup failed");
        }
    }

    private static CFTypeRef constant(String name) {
        return new CFTypeRef(LIBRARY.getGlobalVariableAddress(name).getPointer(0));
    }

    private static final class Query implements AutoCloseable {
        final CFStringRef account, service;
        final CFMutableDictionaryRef value;
        Query(String id) {
            account = CFStringRef.createCFString(id); service = CFStringRef.createCFString(SERVICE);
            // No callbacks: all referenced CF values stay alive until the synchronous call returns.
            value = CF.CFDictionaryCreateMutable(null, new CFIndex(0), Pointer.NULL, Pointer.NULL);
            value.setValue(constant("kSecClass"), constant("kSecClassGenericPassword"));
            value.setValue(constant("kSecAttrService"), service);
            value.setValue(constant("kSecAttrAccount"), account);
            value.setValue(constant("kSecUseAuthenticationUI"), constant("kSecUseAuthenticationUIFail"));
        }
        @Override public void close() { value.release(); account.release(); service.release(); }
    }
}
