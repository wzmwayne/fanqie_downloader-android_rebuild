package com.example.fqdownloader.crypto;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.zip.GZIPInputStream;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class FqCrypto {

    public static final String REG_KEY = "ac25c67ddd8f38c1b37a2348828e222e";

    private final SecretKeySpec secretKey;

    public FqCrypto(String hexKey) throws Exception {
        if (hexKey == null || hexKey.length() != 32) {
            throw new IllegalArgumentException("Key must be 32 hex chars, got: " +
                (hexKey != null ? hexKey.length() : "null"));
        }
        byte[] keyBytes = hexStringToByteArray(hexKey);
        if (keyBytes.length != 16) {
            throw new IllegalArgumentException("Key must be 16 bytes after hex decode");
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    public byte[] encrypt(byte[] data, byte[] iv) throws Exception {
        if (iv.length != 16) throw new IllegalArgumentException("IV must be 16 bytes");
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, new IvParameterSpec(iv));
        return cipher.doFinal(data);
    }

    public byte[] decrypt(String encodedData) throws Exception {
        byte[] decodedData = Base64.getDecoder().decode(encodedData);
        if (decodedData.length < 16) throw new IllegalArgumentException("Encrypted data too short");
        byte[] iv = new byte[16];
        System.arraycopy(decodedData, 0, iv, 0, 16);
        byte[] encryptedData = new byte[decodedData.length - 16];
        System.arraycopy(decodedData, 16, encryptedData, 0, encryptedData.length);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(iv));
        return cipher.doFinal(encryptedData);
    }

    public String newRegisterKeyContent(String serverDeviceId, String strVal) throws Exception {
        long deviceId = Long.parseLong(serverDeviceId);
        long val = Long.parseLong(strVal);
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(deviceId);
        buffer.putLong(val);
        byte[] combinedBytes = buffer.array();
        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);
        byte[] encryptedData = encrypt(combinedBytes, iv);
        byte[] finalData = new byte[iv.length + encryptedData.length];
        System.arraycopy(iv, 0, finalData, 0, iv.length);
        System.arraycopy(encryptedData, 0, finalData, iv.length, encryptedData.length);
        return Base64.getEncoder().encodeToString(finalData);
    }

    public static String decryptRegisterKey(String registerkeyResponseKey, String aesKeyHex) throws Exception {
        byte[] raw = Base64.getDecoder().decode(registerkeyResponseKey);
        if (raw.length < 16) throw new IllegalArgumentException("Encrypted data too short");
        byte[] iv = new byte[16];
        System.arraycopy(raw, 0, iv, 0, 16);
        byte[] cipherText = new byte[raw.length - 16];
        System.arraycopy(raw, 16, cipherText, 0, cipherText.length);
        byte[] keyBytes = hexStringToByteArray(aesKeyHex);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new IvParameterSpec(iv));
        byte[] decrypted = cipher.doFinal(cipherText);
        return byteArrayToHexString(decrypted);
    }

    public static String getRealKey(String registerkeyResponseKey) throws Exception {
        String fullKeyHex = decryptRegisterKey(registerkeyResponseKey, REG_KEY);
        if (fullKeyHex.length() >= 32) return fullKeyHex.substring(0, 32);
        else throw new IllegalArgumentException("Decrypted key too short");
    }

    public static byte[] hexStringToByteArray(String hexString) {
        int len = hexString.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hexString.charAt(i), 16) << 4)
                                 + Character.digit(hexString.charAt(i + 1), 16));
        }
        return data;
    }

    public static String byteArrayToHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02X", b));
        return sb.toString();
    }

    public static String decryptAndDecompressContent(String encryptedContent, String keyHex) throws Exception {
        FqCrypto crypto = new FqCrypto(keyHex);
        byte[] decryptedBytes = crypto.decrypt(encryptedContent);
        if (decryptedBytes.length >= 2 &&
            (decryptedBytes[0] & 0xff) == 0x1f &&
            (decryptedBytes[1] & 0xff) == 0x8b) {
            return decompressGzip(decryptedBytes);
        } else {
            return new String(decryptedBytes, StandardCharsets.UTF_8);
        }
    }

    public static String decompressGzip(byte[] compressedData) throws IOException {
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(compressedData));
             InputStreamReader r = new InputStreamReader(gz, StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[1024];
            int n;
            while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
            return sb.toString();
        }
    }
}
