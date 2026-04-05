package com.example.client.dto;

public record DecryptedMessage(
        String sender,
        String recipient,
        String content,
        String timestamp,
        boolean success
) {}