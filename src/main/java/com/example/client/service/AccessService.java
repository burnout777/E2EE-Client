package com.example.client.service;

import com.example.client.dto.LoadedIdentity;
import com.example.client.util.CryptoUtil;
import com.example.client.util.KeyStorage;
import okhttp3.*;
import java.io.IOException;
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
                        // Sync Pre-keys (Updates the identity's preKey map)
                        //syncPreKeys(authClient, identity);

                        // Perform rotation and get the new identity
                        identity = kms.rotateIdentity(identity, passwordHash);

                        // Final check on pre-key counts
                        if (kms.getServerPreKeyCount(username) < 10) {
                            kms.replenishPreKeys(identity);
                        }

                        KeyStorage.saveIdentityEncrypted(username, passwordHash, identity);

                    } catch (Exception e) {
                        System.err.println("Maintenance warning: " + e.getMessage());
                    }

                    // Return the new version of the identity to the UI
                    callback.onSuccess(authClient, identity, passwordHash);


                    // --- FLOW B: NEW USER ---
                } else {
                    KeyPair identityKeyPair = CryptoUtil.generateKeyPair();
                    String pubKeyStr = CryptoUtil.keyToString(identityKeyPair.getPublic());

                    if (registerOnServer(username, passwordHash, pubKeyStr)) {
                        LoadedIdentity identity = new LoadedIdentity(username, identityKeyPair, System.currentTimeMillis());

                        OkHttpClient authClient = createAuthenticatedClient(username, passwordHash);

                        // Populate initial pre-keys
                        uploadPreKeys(authClient, identity);

                        // Final save for new user
                        KeyStorage.saveIdentityEncrypted(username, passwordHash, identity);
                        callback.onSuccess(authClient, identity, passwordHash);
                    } else {
                        callback.onFailure("SERVER REGISTRATION FAILED");
                    }
                }
            } catch (Exception e) {
                callback.onFailure("CRYPTO ERROR: " + e.getMessage());
            }
        }).start();
    }

    private boolean registerOnServer(String u, String p, String key) throws IOException {
        String json = String.format("{\"username\":\"%s\",\"password\":\"%s\",\"publicKey\":\"%s\"}", u, p, key);
        Request request = new Request.Builder()
                .url(SERVER_URL + "/register")
                .post(RequestBody.create(json, JSON))
                .build();

        try (Response response = baseClient.newCall(request).execute()) {
            return response.isSuccessful();
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