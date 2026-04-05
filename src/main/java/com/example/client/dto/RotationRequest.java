package com.example.client.dto;

public record RotationRequest(
        String username,
        String newKey,
        String oldKey,
        String transitionTimestamp // The moment the old key was born
) {}