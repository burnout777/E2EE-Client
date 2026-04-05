package com.example.client.util;

import com.example.client.dto.LoadedIdentity;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Map;
import java.util.Properties;

public class KeyStorage {

    private static final int AES_KEY_SIZE = 256;
    private static final int GCM_IV_LENGTH = 12;
    private static final int SALT_LENGTH = 16;
    private static final int ITERATIONS = 65536;

    private static String getFilename(String username) {
        return username.trim() + ".secure";
    }

    public static boolean identityExists(String username) {
        return new File(getFilePath(username)).exists();
    }

    private static String getFilePath(String username) {
        return java.nio.file.Paths.get(com.example.client.config.AppConfig.KEYS_DIR,
                username.trim() + ".secure").toString();
    }

    public static void saveIdentityEncrypted(String username, String password, LoadedIdentity identity) {
        try {
            File keyDir = new File(com.example.client.config.AppConfig.KEYS_DIR);

            if (!keyDir.exists()) keyDir.mkdirs();

            Properties prop = new Properties();
            prop.setProperty("username", username);

            prop.setProperty("publicKey", CryptoUtil.keyToString(identity.identityKeys.getPublic()));
            prop.setProperty("privateKey", CryptoUtil.keyToString(identity.identityKeys.getPrivate()));
            prop.setProperty("createdAt", String.valueOf(identity.createdAt));

            // Save Pre-Keys (X25519)
            for (Map.Entry<String, String> entry : identity.preKeys.entrySet()) {
                prop.setProperty("preKey_" + entry.getKey(), entry.getValue());
            }

            // Encrypt and Write to file
            StringWriter writer = new StringWriter();
            prop.store(writer, null);
            byte[] dataToEncrypt = writer.toString().getBytes(StandardCharsets.UTF_8);

            byte[] salt = new byte[SALT_LENGTH];
            byte[] iv = new byte[GCM_IV_LENGTH];
            SecureRandom random = new SecureRandom();
            random.nextBytes(salt);
            random.nextBytes(iv);

            SecretKey secretKey = deriveKey(password, salt);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(128, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);
            byte[] cipherText = cipher.doFinal(dataToEncrypt);

            try (FileOutputStream fos = new FileOutputStream(getFilePath(username))) {
                fos.write(salt);
                fos.write(iv);
                fos.write(cipherText);
            }
            System.out.println("Identity & Pre-Keys saved to " + getFilePath(username));

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static LoadedIdentity loadIdentityEncrypted(String username, String password) {
        File file = new File(getFilePath(username));
        if (!file.exists()) return null;

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] salt = new byte[SALT_LENGTH];
            if (fis.read(salt) != SALT_LENGTH) throw new IOException("Corrupted file");

            byte[] iv = new byte[GCM_IV_LENGTH];
            if (fis.read(iv) != GCM_IV_LENGTH) throw new IOException("Corrupted file");

            byte[] cipherText = fis.readAllBytes();

            SecretKey secretKey = deriveKey(password, salt);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(128, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

            byte[] decryptedData = cipher.doFinal(cipherText);
            String propString = new String(decryptedData, StandardCharsets.UTF_8);

            Properties prop = new Properties();
            prop.load(new StringReader(propString));

            long createdAt = Long.parseLong(prop.getProperty("createdAt", String.valueOf(System.currentTimeMillis())));

            // load identity
            KeyPair identityKeys = new KeyPair(
                    CryptoUtil.stringToPublicKey(prop.getProperty("publicKey"), "Ed25519"),
                    CryptoUtil.stringToPrivateKey(prop.getProperty("privateKey"), "Ed25519")
            );

            LoadedIdentity loaded = new LoadedIdentity(username, identityKeys, createdAt);

            for (String name : prop.stringPropertyNames()) {
                if (name.startsWith("preKey_")) {
                    String pubKeyStr = name.substring(7); // Remove "preKey_" prefix
                    String privKeyStr = prop.getProperty(name);
                    loaded.preKeys.put(pubKeyStr, privKeyStr);
                }
            }
            return loaded;
        } catch (Exception e) {
            return null;
        }
    }

    private static SecretKey deriveKey(String password, byte[] salt) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, AES_KEY_SIZE);
        SecretKey tmp = factory.generateSecret(spec);
        return new SecretKeySpec(tmp.getEncoded(), "AES");
    }
}