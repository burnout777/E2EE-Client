package com.example.client.service;

import com.example.client.dto.LoadedIdentity;
import com.example.client.util.CryptoUtil;
import com.example.client.util.KeyStorage;
import okhttp3.*;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.List;
import java.util.Map;

public class AccessService {
    private static final String SERVER_URL = "http://localhost:8080/api/users";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final OkHttpClient baseClient = new OkHttpClient();

    public interface AccessCallback {
        void onSuccess(OkHttpClient authClient, LoadedIdentity identity, String passwordHash);
        void onFailure(String error);
    }

    public interface UsernameCheckCallback {
        void onAvailable();
        void onTaken();
        void onFailure(String error);
    }

    public void checkUsernameAvailability(String username, UsernameCheckCallback callback) {
        new Thread(() -> {
            try {
                String encodedUsername = URLEncoder.encode(username, StandardCharsets.UTF_8);
                Request request = new Request.Builder()
                        .url(SERVER_URL + "/availability/" + encodedUsername)
                        .get()
                        .build();
                try (Response response = baseClient.newCall(request).execute()) {
                    String body = response.body() != null ? response.body().string().trim() : "";

                    if (response.isSuccessful()) {
                        if ("AVAILABLE".equalsIgnoreCase(body)) {
                            callback.onAvailable();
                            return;
                        }
                        callback.onFailure("Unexpected availability response: " + body);
                        return;
                    }

                    if (response.code() == 409 && "TAKEN".equalsIgnoreCase(body)) {
                        callback.onTaken();
                        return;
                    }

                    callback.onFailure("Could not check username.");
                }
            } catch (Exception e) {
                callback.onFailure("Could not check username: " + e.getMessage());
            }
        }).start();
    }

    public void attemptAccess(String username, String rawPassword, AccessCallback callback) {
        new Thread(() -> {
            try {
                String passwordHash = CryptoUtil.hashPassword(rawPassword, username);

                // --- FLOW A: RETURNING USER ---
                if (KeyStorage.identityExists(username)) {
                    LoadedIdentity identity = KeyStorage.loadIdentityEncrypted(username, passwordHash);
                    if (identity == null) {
                        callback.onFailure("INVALID PASSWORD");
                        return;
                    }

                    OkHttpClient authClient = createAuthenticatedClient(username, passwordHash);
                    KeyManagementService kms = new KeyManagementService(authClient);

                    try {
                        // Perform rotation and get the new identity if it's been over a week since last rotation
                        long lastRotation = kms.getLastRotation(username);
                        long currentTime = System.currentTimeMillis();

                        boolean olderThanAWeek =
                                (currentTime - (lastRotation * 1000L)) > (7L * 24 * 60 * 60 * 1000);

                        if (olderThanAWeek) {
                            identity = kms.rotateIdentity(identity, passwordHash);
                        }

                        // Check pre-key count and if it's too low replenish
                        if (kms.getServerPreKeyCount(username) < 10) {
                            kms.replenishPreKeys(identity);
                        }

                        // Persist changes
                        KeyStorage.saveIdentityEncrypted(username, passwordHash, identity);

                    } catch (Exception e) {
                        System.err.println("Maintenance warning: " + e.getMessage());
                    }

                    // Return the new version of the identity to the UI
                    callback.onSuccess(authClient, identity, passwordHash);


                    // --- FLOW B: NEW USER ---
                }  else {
                    KeyPair identityKeyPair = CryptoUtil.generateKeyPair();
                    String pubKeyStr = CryptoUtil.keyToString(identityKeyPair.getPublic());

                    String registrationResult = registerOnServer(username, passwordHash, pubKeyStr);

                    if ("SUCCESS".equals(registrationResult)) {
                        LoadedIdentity identity = new LoadedIdentity(
                                username,
                                identityKeyPair,
                                System.currentTimeMillis()
                        );
                        OkHttpClient authClient = createAuthenticatedClient(username, passwordHash);
                        uploadPreKeys(authClient, identity);
                        KeyStorage.saveIdentityEncrypted(username, passwordHash, identity);
                        callback.onSuccess(authClient, identity, passwordHash);
                    } else if ("USERNAME_TAKEN".equals(registrationResult)) {
                        callback.onFailure("USERNAME TAKEN");
                    } else {
                        callback.onFailure(registrationResult);
                    }
                }
            } catch (Exception e) {
                callback.onFailure("CRYPTO ERROR: " + e.getMessage());
            }
        }).start();
    }

    private String registerOnServer(String u, String p, String key) throws IOException {
        String json = String.format("{\"username\":\"%s\",\"password\":\"%s\",\"publicKey\":\"%s\"}", u, p, key);
        Request request = new Request.Builder()
                .url(SERVER_URL + "/register")
                .post(RequestBody.create(json, JSON))
                .build();

        try (Response response = baseClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string().trim() : "";

            if (response.isSuccessful()) {
                return "SUCCESS";
            }

            if ((response.code() == 400 || response.code() == 409) && "Username taken".equalsIgnoreCase(body)) {
                return "USERNAME_TAKEN";
            }

            return "SERVER REGISTRATION FAILED: " + body;
        }
    }

    private void uploadPreKeys(OkHttpClient authClient, LoadedIdentity identity) throws Exception {
        List<Map<String, String>> keyList = new java.util.ArrayList<>();

        for (int i = 0; i < 10; i++) {
            KeyPair kp = CryptoUtil.generateEncryptionKeyPair();
            String pub = CryptoUtil.keyToString(kp.getPublic());

            identity.preKeys.put(pub, CryptoUtil.keyToString(kp.getPrivate()));

            // Prepare data for server
            java.util.Map<String, String> keyEntry = new java.util.HashMap<>();
            keyEntry.put("username", identity.username);
            keyEntry.put("preKeyVal", pub);
            keyList.add(keyEntry);
        }

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String json = mapper.writeValueAsString(keyList);

        Request req = new Request.Builder()
                .url(SERVER_URL + "/keys/upload")
                .post(RequestBody.create(json, JSON))
                .build();

        try (Response response = authClient.newCall(req).execute()) {
            if (!response.isSuccessful()) {
                System.err.println("Pre-key upload failed: " + response.code());
            }
        }
    }

    private OkHttpClient createAuthenticatedClient(String u, String p) {
        return baseClient.newBuilder().addInterceptor(chain ->
                chain.proceed(chain.request().newBuilder()
                        .header("Authorization", Credentials.basic(u, p))
                        .build())
        ).build();
    }
}