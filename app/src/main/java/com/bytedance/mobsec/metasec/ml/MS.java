package com.bytedance.mobsec.metasec.ml;

import android.util.Log;

/**
 * Proxy class for MobSec ML SDK.
 * The SO calls back to this class via JNI for configuration values.
 */
public class MS {

    private static final String TAG = "MS";
    private static byte[] certData;

    /** Set cert file data (called from app init). */
    public static void setCertData(byte[] data) {
        certData = data;
    }

    /** Called by the SO to get various configuration values. */
    public static Object b(int methodId, int arg2, long arg3, String arg4, Object arg5) {
        switch (methodId) {
            case 65539:
                return "/data/data/com.example.fqdownloader/files/.msdata";
            case 33554433:
            case 33554434:
                return Boolean.TRUE;
            case 16777232:
                return 68132;
            case 16777233:
                return "6.8.1.32";
            case 16777218:
                return certData;
            case 268435470:
                return System.currentTimeMillis();
            default:
                Log.d(TAG, "Unhandled MS method ID: " + methodId);
                return null;
        }
    }

    /** Called by the SO during initialization. */
    public static void a() {
        Log.d(TAG, "MS.a() called");
    }
}
