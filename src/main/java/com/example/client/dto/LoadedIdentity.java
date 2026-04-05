package com.example.client.dto;

import java.security.KeyPair;
import java.util.HashMap;
import java.util.Map;

public class LoadedIdentity {
    public final String username;
    public final KeyPair identityKeys;
    public final long createdAt;

    public final Map<String, String> preKeys = new HashMap<>();

    public LoadedIdentity(String username, KeyPair identityKeys, long createdAt) {
        this.username = username;
        this.identityKeys = identityKeys;
        this.createdAt = createdAt;
    }

    public String getPublicKeyString() {
        try {
            return com.example.client.util.CryptoUtil.keyToString(identityKeys.getPublic());
        } catch (Exception e) {
            return null;
        }
    }

}