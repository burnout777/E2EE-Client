package com.example.client.service;

import com.example.client.config.AppConfig;
import com.example.client.config.CryptoConfig;
import com.example.client.dto.KeyBundle;
import com.example.client.dto.LoadedIdentity;
import com.example.client.dto.MsgPayload;
import com.example.client.util.CryptoUtil;
import com.example.client.dto.DecryptedMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.io.IOException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.*;
import java.util.function.Consumer;

public class MessageService {
    private final String username;
    private final LoadedIdentity myIdentity;
    private final String hashedPassword;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient authClient;

    private StompSession stompSession;
    private WebSocketStompClient stompClient; // Track client to prevent duplicates

    public MessageService(String username, LoadedIdentity identity, String hashedPassword, OkHttpClient authClient) {
        this.username = username;
        this.myIdentity = identity;
        this.hashedPassword = hashedPassword;
        this.authClient = authClient;
    }

    public void connect(KeyManagementService kms, Consumer<DecryptedMessage> onMessageReceived) {
        if (stompSession != null && stompSession.isConnected()) { return; }

        String wsUrl = AppConfig.getProperty("server.ws.url", "ws://localhost:8080/ws-secure-comm");

        if (stompClient == null) {
            this.stompClient = new WebSocketStompClient(new SockJsClient(
                    List.of(new WebSocketTransport(new StandardWebSocketClient()))));
            this.stompClient.setMessageConverter(new StringMessageConverter());
        }

        String auth = Base64.getEncoder().encodeToString((username + ":" + hashedPassword).getBytes());
        org.springframework.web.socket.WebSocketHttpHeaders headers = new org.springframework.web.socket.WebSocketHttpHeaders();
        headers.add("Authorization", "Basic " + auth);

        stompClient.connectAsync(wsUrl, headers, new StompSessionHandlerAdapter() {
            @Override
            public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                stompSession = session;
                System.out.println(">>> Connected to Secure Chat Server.");

                session.subscribe("/user/queue/messages", new StompFrameHandler() {
                    @Override
                    public java.lang.reflect.Type getPayloadType(StompHeaders h) { return String.class; }

                    @Override
                    public void handleFrame(StompHeaders h, Object payload) {
                        DecryptedMessage decrypted = decryptIncoming((String) payload, kms);
                        if (decrypted != null) {
                            onMessageReceived.accept(decrypted);
                        }
                    }
                });
            }

            @Override
            public void handleException(StompSession s, StompCommand c, StompHeaders h, byte[] p, Throwable ex) {
                System.err.println("STOMP Error: " + ex.getMessage());
            }
        });
    }

    public void sendMessage(String recipient, String plainText, KeyBundle bundle) throws Exception {
        // Derivation
        KeyPair ephemeral = CryptoUtil.generateEncryptionKeyPair();
        PublicKey theirPreKey = CryptoUtil.stringToPublicKey(bundle.preKey(), CryptoConfig.KEY_EXCHANGE_ALGO);

        byte[] salt = new byte[32];
        new SecureRandom().nextBytes(salt);

        byte[] rawSecret = CryptoUtil.performECDH(ephemeral.getPrivate(), theirPreKey);
        byte[] aesKey = CryptoUtil.performHKDF(rawSecret, salt);

        // Sign and Encrypt
        String signature = CryptoUtil.sign(plainText, myIdentity.identityKeys.getPrivate());

        String publickeypart = CryptoUtil.keyToString(myIdentity.identityKeys.getPublic());

        System.err.println("Publickeypart: " + publickeypart);

        CryptoUtil.EncryptedMessage enc = CryptoUtil.encryptAES(aesKey, plainText);

        long timestamp = System.currentTimeMillis();

        // Construct Payload
        MsgPayload payload = new MsgPayload();
        payload.recipientUser = recipient;
        payload.signature = signature;
        payload.cipherText = enc.cipherText;
        payload.iv = enc.iv;
        payload.ephemeralPublicKey = CryptoUtil.keyToString(ephemeral.getPublic());
        payload.salt = Base64.getEncoder().encodeToString(salt);
        payload.sender = this.username;
        payload.timestamp =  timestamp;

        if (stompSession != null && stompSession.isConnected()) {
            stompSession.send("/app/chat", objectMapper.writeValueAsString(payload));
        } else {
            throw new IOException("Not connected to chat server.");
        }
    }

    public List<DecryptedMessage> fetchAndDecryptHistory(KeyManagementService kms) {
        List<DecryptedMessage> history = new ArrayList<>();
        String url = AppConfig.getMessageUrl() + "/" + username;

        Request request = new Request.Builder().url(url).get().build();

        try (Response response = authClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                System.err.println(">>> [ERROR] Server returned code: " + response.code());
                return history;
            }

            String responseBody = response.body().string();
            JsonNode root = objectMapper.readTree(responseBody);

            for (JsonNode node : root) {
                String msgId = node.has("id") ? node.get("id").asText() : "unknown";
                String sender = node.has("sender") ? node.get("sender").asText() : "unknown";
                long ts = node.has("timestamp") ? node.get("timestamp").asLong() : 0L;

                try {
                    DecryptedMessage d = decryptIncoming(node.toString(), kms);
                    if (d != null) { history.add(d); }
                } catch (Exception e) {
                    System.err.println(">>> [SKIP] Could not decrypt Msg ID: " + msgId + " because: " + e.getMessage());
                }
            }
        } catch (Exception e) { e.printStackTrace(); }

        return history;
    }

    public DecryptedMessage decryptIncoming(String jsonPayload, KeyManagementService kms) {
        try {
            JsonNode msg = objectMapper.readTree(jsonPayload);

            // Extract metadata
            String senderName = msg.path("sender").asText("UNKNOWN");
            String recipientName = msg.path("recipientUser").asText(this.username);
            String timestamp = msg.path("timestamp").asText("UNKNOWN");

            // Extract crypto fields
            String iv = msg.path("iv").asText(null);
            String cipherText = msg.path("cipherText").asText(null);
            String ephemeralKeyStr = msg.path("ephemeralPublicKey").asText(null);
            String saltStr = msg.path("salt").asText(null);
            String signature = msg.path("signature").asText(null);

            // Early exit if the packet is physically incomplete
            if (iv == null || cipherText == null || ephemeralKeyStr == null || saltStr == null) {
                System.err.println("CRITICAL: Message packet is missing required crypto fields.");
                return null;
            }

            // Prep for decryption
            KeyBundle historical = kms.fetchRecipientKeys(senderName, timestamp);
            PublicKey senderIdentityKey = CryptoUtil.stringToPublicKey(historical.identityKey(), "Ed25519");
            PublicKey senderEphemeral = CryptoUtil.stringToPublicKey(ephemeralKeyStr, "X25519");
            byte[] salt = Base64.getDecoder().decode(saltStr);

            for (Map.Entry<String, String> entry : myIdentity.preKeys.entrySet()) {

                try {
                    PrivateKey myPriv = CryptoUtil.stringToPrivateKey(entry.getValue(), "X25519");
                    byte[] aesKey = CryptoUtil.performHKDF(CryptoUtil.performECDH(myPriv, senderEphemeral), salt);

                    // Use the local 'iv' and 'cipherText'
                    String plainText = CryptoUtil.decryptAES(aesKey, iv, cipherText);

                    if (plainText != null && CryptoUtil.verify(plainText, signature, historical.identityKey())) {
                        return new DecryptedMessage(senderName, recipientName, plainText, timestamp, true);
                    }
                    boolean sigValid = CryptoUtil.verify(plainText, signature, historical.identityKey());
                    if (!sigValid) {
                        System.err.println(">>> [CRYPTO] Signature invalid! " +
                                "Message was signed with a different key than what the server has now.");
                    }
                } catch (Exception ignored) { }
            }
        } catch (Exception e) {
            System.err.println("Decryption failed: " + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }

    public String getUsername() { return this.username; }
}
