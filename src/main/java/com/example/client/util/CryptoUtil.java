package com.example.client.util;

import com.example.client.config.CryptoConfig;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.security.MessageDigest;

public class CryptoUtil {

    public static KeyPair generateKeyPair() throws NoSuchAlgorithmException {
        return KeyPairGenerator.getInstance(CryptoConfig.SIGN_ALGO).generateKeyPair();
    }

    public static String sign(String message, PrivateKey privateKey) throws Exception {
        Signature sig = Signature.getInstance(CryptoConfig.SIGN_ALGO);
        sig.initSign(privateKey);
        sig.update(message.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(sig.sign());
    }

    public static boolean verify(String message, String signatureBase64, String publicKeyBase64) {
        try {

            PublicKey publicKey = stringToPublicKey(publicKeyBase64, CryptoConfig.SIGN_ALGO);

            Signature sig = Signature.getInstance(CryptoConfig.SIGN_ALGO);
            sig.initVerify(publicKey);
            sig.update(message.getBytes(StandardCharsets.UTF_8));
            return sig.verify(Base64.getDecoder().decode(signatureBase64));
        } catch (Exception e) {
            return false;
        }
    }

    public static KeyPair generateEncryptionKeyPair() throws NoSuchAlgorithmException {
        return KeyPairGenerator.getInstance(CryptoConfig.KEY_EXCHANGE_ALGO).generateKeyPair();
    }

    public static byte[] performECDH(PrivateKey myPrivate, PublicKey theirPublic) throws Exception {
        KeyAgreement ka = KeyAgreement.getInstance(CryptoConfig.KEY_EXCHANGE_ALGO);
        ka.init(myPrivate);
        ka.doPhase(theirPublic, true);
        return ka.generateSecret();
    }

    public static byte[] performHKDF(byte[] inputKeyMaterial, byte[] salt) throws Exception {
        Mac hmac = Mac.getInstance(CryptoConfig.HMAC_ALGO);
        SecretKeySpec saltKey = new SecretKeySpec(salt, CryptoConfig.HMAC_ALGO);
        hmac.init(saltKey);
        byte[] pseudoRandomKey = hmac.doFinal(inputKeyMaterial);
        SecretKeySpec prk = new SecretKeySpec(pseudoRandomKey, CryptoConfig.HMAC_ALGO);
        hmac.init(prk);
        hmac.update(CryptoConfig.HKDF_INFO.getBytes(StandardCharsets.UTF_8));
        hmac.update((byte) 0x01);
        byte[] output = hmac.doFinal();

        return Arrays.copyOf(output, 16);
    }

    public static EncryptedMessage encryptAES(byte[] aesKey, String plainText) throws Exception {
        byte[] iv = new byte[CryptoConfig.GCM_IV_LENGTH];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance(CryptoConfig.AES_TRANSFORMATION);
        GCMParameterSpec spec = new GCMParameterSpec(CryptoConfig.GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), spec);

        byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

        return new EncryptedMessage(
                Base64.getEncoder().encodeToString(iv),
                Base64.getEncoder().encodeToString(cipherText)
        );
    }

    public static String decryptAES(byte[] aesKey, String ivStr, String cipherTextStr) throws Exception {
        byte[] iv = Base64.getDecoder().decode(ivStr);
        byte[] cipherText = Base64.getDecoder().decode(cipherTextStr);

        Cipher cipher = Cipher.getInstance(CryptoConfig.AES_TRANSFORMATION);
        GCMParameterSpec spec = new GCMParameterSpec(CryptoConfig.GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), spec);

        byte[] plainBytes = cipher.doFinal(cipherText);
        return new String(plainBytes, StandardCharsets.UTF_8);
    }

    // helper methods
    public static String keyToString(Key key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    public static PublicKey stringToPublicKey(String keyStr, String algo) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(keyStr);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        return KeyFactory.getInstance(algo).generatePublic(spec);
    }

    public static PrivateKey stringToPrivateKey(String keyStr, String algo) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(keyStr);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        return KeyFactory.getInstance(algo).generatePrivate(spec);
    }

    public static class EncryptedMessage {
        public String iv;
        public String cipherText;
        public EncryptedMessage(String iv, String cipherText) {
            this.iv = iv;
            this.cipherText = cipherText;
        }
    }

    public static String hashPassword(String password, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance(CryptoConfig.HASH_ALGO);
            String input = password + salt;
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Error hashing password", e);
        }
    }
}