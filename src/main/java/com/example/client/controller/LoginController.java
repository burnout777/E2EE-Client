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
    @FXML private Label statusLabel;
    @FXML private Button backButton;

    private final AccessService accessService = new AccessService();
    private String currentUser = "";

    @FXML
    private void handleNextStep() {
        String id = usernameField.getText().trim();
        if (id.isEmpty()) {
            statusLabel.setText("IDENTIFICATION REQUIRED");
            return;
        }

        this.currentUser = id;

        usernameField.setVisible(false);
        usernameField.setManaged(false);
        passwordField.setVisible(true);
        passwordField.setManaged(true);
        backButton.setVisible(true);
        backButton.setManaged(true);

        if (com.example.client.util.KeyStorage.identityExists(id)) {
            statusLabel.setText("WELCOME BACK, " + id.toUpperCase());
            passwordField.setPromptText("ENTER PASSWORD");
        } else {
            statusLabel.setText("NEW LOCAL USER: " + id.toUpperCase());
            passwordField.setPromptText("CREATE PASSWORD");
        }

        passwordField.requestFocus();
    }

    @FXML
    public void handleAction() {
        System.out.println("handleAction called at: " + System.currentTimeMillis());
        if (processing) {
            System.out.println(">>> GHOST CALL BLOCKED in LoginController");
            return;
        }

        String pass = passwordField.getText();
        if (pass.isEmpty()) {
            statusLabel.setText("PASSWORD REQUIRED");
            return;
        }

        processing = true;
        statusLabel.setStyle("-fx-text-fill: #f39c12;");
        statusLabel.setText("CONNECTING...");
        passwordField.setDisable(true);

        accessService.attemptAccess(currentUser, pass, new AccessService.AccessCallback() {
            @Override
            public void onSuccess(OkHttpClient authClient, LoadedIdentity identity, String passwordHash) {
                Platform.runLater(() -> {
                    try {
                        ChatController chatCtrl = ViewSwitcher.switchTo("/fxml/Chat.fxml");
                        chatCtrl.initSession(currentUser, identity, passwordHash, authClient);
                    } catch (IOException e) {
                        statusLabel.setText("UI ERROR: CHAT FAILED");
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
                    passwordField.clear();
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
        passwordField.clear();
        statusLabel.setText("");

        passwordField.setVisible(false);
        passwordField.setManaged(false);
        backButton.setVisible(false);
        backButton.setManaged(false);
        usernameField.setVisible(true);
        usernameField.setManaged(true);

        usernameField.requestFocus();
    }
}