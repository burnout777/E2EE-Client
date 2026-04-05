package com.example.client.config;

public class CryptoConfig {
    public static final String SIGN_ALGO = "Ed25519";
    public static final String KEY_EXCHANGE_ALGO = "X25519";
    public static final String HMAC_ALGO = "HmacSHA256";
    public static final String HASH_ALGO = "SHA-256";
    public static final String AES_TRANSFORMATION = "AES/GCM/NoPadding";
    public static final String HKDF_INFO = "SecureCommv1";
    public static final int GCM_IV_LENGTH = 12;
    public static final int GCM_TAG_LENGTH = 128;
}