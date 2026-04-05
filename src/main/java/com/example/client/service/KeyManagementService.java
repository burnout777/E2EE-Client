package com.example.client.service;

import com.example.client.config.AppConfig;
import com.example.client.dto.KeyBundle;
import com.example.client.dto.LoadedIdentity;
import com.example.client.dto.RotationRequest;
import com.example.client.util.CryptoUtil;
import com.example.client.util.KeyStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import java.io.IOException;
import java.security.KeyPair;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class KeyManagementService {
    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient authClient;
    private final String baseUrl;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    public KeyManagementService(OkHttpClient authClient) {
        this.authClient = authClient;
        this.baseUrl = AppConfig.getBaseUrl();
    }

    public int getServerPreKeyCount(String username) {
        String url = String.format("%s/%s/count", baseUrl, username);
        Request request = new Request.Builder().url(url).get().build();

        try (Response response = authClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                return Integer.parseInt(response.body().string().trim());
            }
        } catch (Exception e) {
            System.err.println("Pre-key check failed: " + e.getMessage());
        }
        return 0;
    }

    public void replenishPreKeys(LoadedIdentity identity) throws Exception {
        List<Map<String, String>> keyList = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            KeyPair kp = CryptoUtil.generateEncryptionKeyPair();
            String pub = CryptoUtil.keyToString(kp.getPublic());
            identity.preKeys.put(pub, CryptoUtil.keyToString(kp.getPrivate()));
            keyList.add(Map.of("username", identity.username, "preKeyVal", pub));
        }

        Request req = new Request.Builder()
                .url(baseUrl + "/keys/upload")
                .post(RequestBody.create(mapper.writeValueAsString(keyList), JSON))
                .build();

        try (Response response = authClient.newCall(req).execute()) {
            if (!response.isSuccessful()) throw new IOException("Pre-key upload failed");
        }
    }

    public KeyBundle fetchRecipientKeys(String targetUser, String timestamp) throws Exception {

        HttpUrl.Builder urlBuilder = HttpUrl.parse(baseUrl + "/keys/" + targetUser).newBuilder();
        if (timestamp != null) {
            urlBuilder.addQueryParameter("timestamp", timestamp);
        }

        Request request = new Request.Builder()
                .url(urlBuilder.build())
                .build();

        try (Response response = authClient.newCall(request).execute()) {
            String responseBody = response.body().string();
            if (!response.isSuccessful()) throw new IOException("Keys not found: " + responseBody);

            JsonNode node = mapper.readTree(responseBody);

            String identity = node.get("identityKey").asText();

            String preKey = null;
            if (node.has("preKey") && !node.get("preKey").isNull()) {
                JsonNode pkNode = node.get("preKey");
                preKey = pkNode.isObject() ? pkNode.get("preKeyVal").asText() : pkNode.asText();
            }

            return new KeyBundle(identity, preKey);
        }
    }

    public LoadedIdentity rotateIdentity(LoadedIdentity oldIdentity, String password) {
        try {
            KeyPair kp = CryptoUtil.generateKeyPair();

            String newPublicKeyStr = CryptoUtil.keyToString(kp.getPublic());

            String currentTimeStamp = String.valueOf(LocalDateTime.now().toEpochSecond(ZoneOffset.UTC));

            KeyBundle oldPublicKey = fetchRecipientKeys(oldIdentity.username, currentTimeStamp);
            String oldPublicKeyStr = oldPublicKey.identityKey();

            long transitionTimestamp = System.currentTimeMillis();
            String transitionTimestampStr = String.valueOf(transitionTimestamp);

            RotationRequest rotationReq = new RotationRequest(
                    oldIdentity.username,
                    newPublicKeyStr,
                    oldPublicKeyStr,
                    transitionTimestampStr
            );

            RequestBody body = RequestBody.create(mapper.writeValueAsString(rotationReq), JSON);
            Request request = new Request.Builder()
                    .url(baseUrl + "/keys/rotate")
                    .post(body)
                    .build();

            try (Response response = authClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    LoadedIdentity newIdentity = new LoadedIdentity(
                            oldIdentity.username,
                            kp,
                            transitionTimestamp
                    );

                    newIdentity.preKeys.putAll(oldIdentity.preKeys);
                    KeyStorage.saveIdentityEncrypted(newIdentity.username, password, newIdentity);

                    System.out.println("Identity rotated successfully on server and disk.");
                    return newIdentity;
                } else {
                    System.err.println("Server rejected rotation: " + response.code());
                }
            }
        } catch (Exception e) {
            System.err.println("Rotation failed: " + e.getMessage());
        }
        return oldIdentity;
    }

    public boolean auditLedger() throws Exception {
        Request request = new Request.Builder()
                .url(baseUrl + "/ledger/audit")
                .get()
                .build();

        try (Response response = authClient.newCall(request).execute()) {
            return response.isSuccessful();
        }
    }
}