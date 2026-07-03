package ms.bd.c;

/**
 * Proxy class expected by libmetasec_ml.so JNI_OnLoad.
 * Native methods are registered by the SO at load time.
 */
public class m {
    // Guesses for the native signature method - the SO's RegisterNatives
    // will match the correct one. Unused declarations are harmless.
    public static native String a(String url, String headers);
    public static native String b(String url, String headers);
}
