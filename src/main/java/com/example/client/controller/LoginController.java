package com.example.client.controller;

import com.example.client.dto.LoadedIdentity;
import com.example.client.service.AccessService;
import com.example.client.ui.ViewSwitcher;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.StackPane;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import okhttp3.OkHttpClient;
import java.io.IOException;
import java.net.URL;

public class LoginController {

    private boolean processing = false;

    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private PasswordField confirmPasswordField;
    @FXML private Label statusLabel;
    @FXML private Button backButton;

    private final AccessService accessService = new AccessService();
    private String currentUser = "";
    private boolean registeringNewUser = false;

    @FXML
    private void handleNextStep() {
        if (processing) {
            return;
        }

        String id = usernameField.getText().trim();
        if (id.isEmpty()) {
            statusLabel.setStyle("-fx-text-fill: #f39c12;");
            statusLabel.setText("IDENTIFICATION REQUIRED");
            return;
        }

        this.currentUser = id;

        // Existing local identity -> login flow immediately
        if (com.example.client.util.KeyStorage.identityExists(id)) {
            registeringNewUser = false;

            usernameField.setVisible(false);
            usernameField.setManaged(false);
            passwordField.setVisible(true);
            passwordField.setManaged(true);
            confirmPasswordField.setVisible(false);
            confirmPasswordField.setManaged(false);
            confirmPasswordField.clear();
            backButton.setVisible(true);
            backButton.setManaged(true);

            statusLabel.setStyle("-fx-text-fill: #666666;");
            statusLabel.setText("WELCOME BACK, " + id.toUpperCase());
            passwordField.setPromptText("ENTER PASSWORD");
            passwordField.requestFocus();
            return;
        }

        // No local identity -> check server before asking for password
        processing = true;
        usernameField.setDisable(true);
        statusLabel.setStyle("-fx-text-fill: #f39c12;");
        statusLabel.setText("CHECKING USERNAME...");

        accessService.checkUsernameAvailability(id, new AccessService.UsernameCheckCallback() {
            @Override
            public void onAvailable() {
                Platform.runLater(() -> {
                    registeringNewUser = true;
                    processing = false;

                    usernameField.setDisable(false);
                    usernameField.setVisible(false);
                    usernameField.setManaged(false);

                    passwordField.setVisible(true);
                    passwordField.setManaged(true);
                    confirmPasswordField.setVisible(true);
                    confirmPasswordField.setManaged(true);
                    confirmPasswordField.clear();
                    backButton.setVisible(true);
                    backButton.setManaged(true);

                    statusLabel.setStyle("-fx-text-fill: #666666;");
                    statusLabel.setText("CREATE ACCOUNT: " + id.toUpperCase());
                    passwordField.setPromptText("CREATE PASSWORD");
                    confirmPasswordField.setPromptText("CONFIRM PASSWORD");
                    passwordField.requestFocus();
                });
            }

            @Override
            public void onTaken() {
                Platform.runLater(() -> {
                    registeringNewUser = false;
                    processing = false;

                    usernameField.setDisable(false);
                    statusLabel.setStyle("-fx-text-fill: #f39c12;");
                    statusLabel.setText("USERNAME TAKEN ON SERVER");
                    usernameField.requestFocus();
                });
            }

            @Override
            public void onFailure(String error) {
                Platform.runLater(() -> {
                    registeringNewUser = false;
                    processing = false;

                    usernameField.setDisable(false);
                    statusLabel.setStyle("-fx-text-fill: #e74c3c;");
                    statusLabel.setText(error);
                    usernameField.requestFocus();
                });
            }
        });
    }

    @FXML
    public void handleAction() {
        System.out.println("handleAction called at: " + System.currentTimeMillis());
        if (processing) {
            System.out.println(">>> GHOST CALL BLOCKED in LoginController");
            return;
        }

        String pass = passwordField.getText();
        String confirmPass = confirmPasswordField.getText();
        if (pass.isEmpty()) {
            statusLabel.setStyle("-fx-text-fill: #f39c12;");
            statusLabel.setText("PASSWORD REQUIRED");
            return;
        }

        if (registeringNewUser) {
            if (confirmPass.isEmpty()) {
                statusLabel.setStyle("-fx-text-fill: #f39c12;");
                statusLabel.setText("CONFIRM PASSWORD");
                confirmPasswordField.requestFocus();
                return;
            }

            if (!pass.equals(confirmPass)) {
                statusLabel.setStyle("-fx-text-fill: #e74c3c;");
                statusLabel.setText("PASSWORDS DO NOT MATCH");
                passwordField.clear();
                confirmPasswordField.clear();
                passwordField.requestFocus();
                return;
            }
        }

        processing = true;
        statusLabel.setStyle("-fx-text-fill: #f39c12;");
        statusLabel.setText(registeringNewUser ? "CREATING ACCOUNT..." : "CONNECTING...");
        passwordField.setDisable(true);
        confirmPasswordField.setDisable(true);

        accessService.attemptAccess(currentUser, pass, new AccessService.AccessCallback() {
            @Override
            public void onSuccess(OkHttpClient authClient, LoadedIdentity identity, String passwordHash) {
                Platform.runLater(() -> {
                    try {
                        ChatController chatCtrl = ViewSwitcher.switchTo("/fxml/Chat.fxml");
                        chatCtrl.initSession(currentUser, identity, passwordHash, authClient);
                    } catch (IOException e) {
                        statusLabel.setStyle("-fx-text-fill: #e74c3c;");
                        statusLabel.setText("UI ERROR: CHAT FAILED");
                        passwordField.setDisable(false);
                        confirmPasswordField.setDisable(false);
                        processing = false;
                    }
                });
            }

            @Override
            public void onFailure(String error) {
                Platform.runLater(() -> {
                    statusLabel.setStyle("-fx-text-fill: #e74c3c;");
                    statusLabel.setText(error);
                    passwordField.setDisable(false);
                    confirmPasswordField.setDisable(false);
                    passwordField.clear();
                    confirmPasswordField.clear();
                    passwordField.requestFocus();
                    processing = false;
                });
            }
        });
    }

    @FXML
    private MediaPlayer mediaPlayer;
    @FXML
    private StackPane mainRoot;
    @FXML
    private MediaView mediaView;

    @FXML
    public void initialize() {
        URL videoUrl = getClass().getResource("/videos/login_video.mp4");
        if (videoUrl != null) {

            Media media = new Media(videoUrl.toExternalForm());
            mediaPlayer = new MediaPlayer(media);
            mediaPlayer.setMute(true);
            mediaView.setMediaPlayer(mediaPlayer);
            mediaView.setPreserveRatio(false);

            mainRoot.widthProperty().addListener((obs, oldVal, newVal) -> {
                mediaView.setFitWidth(newVal.doubleValue());
            });
            mainRoot.heightProperty().addListener((obs, oldVal, newVal) -> {
                mediaView.setFitHeight(newVal.doubleValue());
            });

            mediaPlayer.play();
        }

        javafx.application.Platform.runLater(() -> {
            usernameField.requestFocus();
            mediaView.setFitWidth(mainRoot.getWidth());
            mediaView.setFitHeight(mainRoot.getHeight());
        });
    }

    @FXML
    public void handleBack() {
        processing = false;
        registeringNewUser = false;
        currentUser = "";

        usernameField.clear();
        passwordField.clear();
        confirmPasswordField.clear();

        usernameField.setDisable(false);
        passwordField.setDisable(false);
        confirmPasswordField.setDisable(false);

        statusLabel.setText("");
        statusLabel.setStyle("-fx-text-fill: #666666;");

        passwordField.setVisible(false);
        passwordField.setManaged(false);
        confirmPasswordField.setVisible(false);
        confirmPasswordField.setManaged(false);
        backButton.setVisible(false);
        backButton.setManaged(false);

        usernameField.setVisible(true);
        usernameField.setManaged(true);

        usernameField.requestFocus();
    }
}