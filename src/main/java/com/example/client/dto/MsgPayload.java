package com.example.client.dto;

public class MsgPayload {
    public String recipientUser;
    public String cipherText;
    public String iv;
    public String ephemeralPublicKey;
    public String salt;
    public String sender;
    public long timestamp;
    public String signature;

    public MsgPayload(){}
}