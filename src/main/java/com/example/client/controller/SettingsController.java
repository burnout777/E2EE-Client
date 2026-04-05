package com.example.client.controller;

import com.example.client.dto.LoadedIdentity;
import com.example.client.service.KeyManagementService;
import com.example.client.ui.ViewSwitcher;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import okhttp3.OkHttpClient;

import java.util.Base64;

public class SettingsController {

    @FXML private Label sectionLabel;
    @FXML private VBox profilePanel;
    @FXML private VBox keyPanel;
    @FXML private Label usernameLabel;
    @FXML private Label fingerprintLabel;
    @FXML private Label identityKeyLabel;
    @FXML private Label onetimeKeyCountLabel;
    @FXML private Label keyStatusLabel;

    private String username;
    private LoadedIdentity identity;
    private String passHash;
    private OkHttpClient authClient;
    private KeyManagementService keyManagementService;

    public void initSettings(String username, LoadedIdentity identity,
                             String passHash, OkHttpClient authClient,
                             KeyManagementService keyManagementService) {
        this.username = username;
        this.identity = identity;
        this.passHash = passHash;
        this.authClient = authClient;
        this.keyManagementService = keyManagementService;

        populateProfile();
        showProfile();
    }

    @FXML
    private void showProfile() {
        sectionLabel.setText("PROFILE");
        profilePanel.setVisible(true);
        keyPanel.setVisible(false);
    }

    @FXML
    private void showKeyManagement() {
        sectionLabel.setText("KEY MANAGEMENT");
        profilePanel.setVisible(false);
        keyPanel.setVisible(true);
        keyStatusLabel.setText("");
        refreshKeyPanel();
    }

    private void populateProfile() {
        usernameLabel.setText("USER: " + username.toUpperCase());
        try {
            byte[] pubEncoded = identity.identityKeys.getPublic().getEncoded();
            fingerprintLabel.setText("FINGERPRINT: " + fingerprintHex(pubEncoded));
        } catch (Exception e) {
            fingerprintLabel.setText("FINGERPRINT: UNAVAILABLE");
        }
    }

    private void refreshKeyPanel() {
        try {
            String pubKey = Base64.getEncoder()
                    .encodeToString(identity.identityKeys.getPublic().getEncoded());
            identityKeyLabel.setText("IDENTITY KEY: " + truncate(pubKey, 40) + "...");
        } catch (Exception e) {
            identityKeyLabel.setText("IDENTITY KEY: UNAVAILABLE");
        }

        onetimeKeyCountLabel.setText("ONE-TIME KEYS REMAINING: CHECKING...");

        new Thread(() -> {
            try {
                int count = keyManagementService.getServerPreKeyCount(username);
                Platform.runLater(() ->
                        onetimeKeyCountLabel.setText("ONE-TIME KEYS REMAINING: " + count));
            } catch (Exception e) {
                Platform.runLater(() ->
                        onetimeKeyCountLabel.setText("ONE-TIME KEYS REMAINING: ERROR"));
            }
        }).start();
    }

    @FXML
    private void handleReplenishKeys() {
        setStatus("UPLOADING KEYS...", "#888888");

        new Thread(() -> {
            try {
                keyManagementService.replenishPreKeys(identity);
                int newCount = keyManagementService.getServerPreKeyCount(username);
                Platform.runLater(() -> {
                    onetimeKeyCountLabel.setText("ONE-TIME KEYS REMAINING: " + newCount);
                    setStatus("KEYS REPLENISHED OK", "#00FF00");
                });
            } catch (Exception e) {
                Platform.runLater(() ->
                        setStatus("ERROR: " + e.getMessage(), "#FF3333"));
            }
        }).start();
    }

    @FXML
    private void handleRotateIdentity() {
        setStatus("ROTATING IDENTITY...", "#888888");

        new Thread(() -> {
            try {
                LoadedIdentity newIdentity = keyManagementService.rotateIdentity(identity, passHash);

                // Check whether rotation actually succeeded by comparing public keys
                boolean rotated = !newIdentity.identityKeys.getPublic()
                        .equals(identity.identityKeys.getPublic());

                if (rotated) {
                    this.identity = newIdentity;
                    Platform.runLater(() -> {
                        populateProfile();   // update fingerprint on profile panel
                        refreshKeyPanel();   // update key label
                        setStatus("IDENTITY ROTATED — NEW KEY ACTIVE", "#00FF00");
                    });
                } else {
                    Platform.runLater(() ->
                            setStatus("ROTATION REJECTED BY SERVER", "#FF3333"));
                }
            } catch (Exception e) {
                Platform.runLater(() ->
                        setStatus("ERROR: " + e.getMessage(), "#FF3333"));
            }
        }).start();
    }

    @FXML
    private void handleAuditLedger() {
        setStatus("AUDITING LEDGER...", "#888888");

        new Thread(() -> {
            try {
                boolean passed = keyManagementService.auditLedger();
                Platform.runLater(() ->
                        setStatus(passed ? "AUDIT: PASSED — LEDGER INTACT" : "AUDIT: FAILED — LEDGER COMPROMISED",
                                passed ? "#00FF00" : "#FF3333"));
            } catch (Exception e) {
                Platform.runLater(() ->
                        setStatus("ERROR: " + e.getMessage(), "#FF3333"));
            }
        }).start();
    }

    @FXML
    private void handleLogout() {
        try {
            ViewSwitcher.switchTo("/fxml/Login.fxml");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @FXML
    private void handleBack() {
        try {
            ChatController chatController = ViewSwitcher.switchTo("/fxml/Chat.fxml");
            chatController.initSession(username, identity, passHash, authClient);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void setStatus(String message, String hexColor) {
        keyStatusLabel.setStyle(
                "-fx-font-family: 'Monospaced'; -fx-font-size: 11px; -fx-text-fill: " + hexColor + ";"
        );
        keyStatusLabel.setText(message);
    }

    private String fingerprintHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(bytes.length, 20); i++) {
            if (i > 0 && i % 4 == 0) sb.append(" ");
            sb.append(String.format("%02X", bytes[i]));
        }
        return sb.toString();
    }

    private String truncate(String s, int len) {
        return s.length() > len ? s.substring(0, len) : s;
    }
}