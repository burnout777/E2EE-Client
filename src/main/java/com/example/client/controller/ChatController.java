package com.example.client.controller;

import com.example.client.dto.DecryptedMessage;
import com.example.client.dto.KeyBundle;
import com.example.client.dto.LoadedIdentity;
import com.example.client.service.KeyManagementService;
import com.example.client.service.MessageService;
import com.example.client.ui.MessageCell;
import com.example.client.ui.ViewSwitcher;
import com.example.client.util.DatabaseManager;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.StackPane;
import okhttp3.OkHttpClient;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ChatController {
    @FXML
    private ListView<String> contactList;
    @FXML
    private ListView<String[]> messageArea;
    @FXML
    private Label activeChatLabel;
    @FXML
    private TextField messageInput;
    @FXML
    private StackPane newChannelContainer;
    @FXML
    private Button newChannelBtn;

    private MessageService messageService;
    private KeyManagementService keyManagementService;
    private LoadedIdentity identity;
    private String passHash;
    private OkHttpClient authClient;
    private String currentRecipient = null;
    private List<DecryptedMessage> fullHistory = new ArrayList<>();

    public void initSession(String username, LoadedIdentity identity, String passHash, OkHttpClient authClient) {
        this.identity = identity;
        this.passHash = passHash;
        this.authClient = authClient;
        initLocalDatabase(username);

        this.keyManagementService = new KeyManagementService(authClient);
        this.messageService = new MessageService(username, identity, passHash, authClient);

        contactList.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) switchChat(newVal);
        });

        messageService.connect(keyManagementService, msg -> {
            DatabaseManager.saveMessage(msg.sender(), msg.recipient(), msg.content(), msg.timestamp());

            Platform.runLater(() -> {
                updateContactList();

                if (currentRecipient != null) {
                    String myName = messageService.getUsername();

                    boolean isIncomingFromActive = msg.sender().equalsIgnoreCase(currentRecipient)
                            && msg.recipient().equalsIgnoreCase(myName);

                    boolean isOutgoingToActive = msg.sender().equalsIgnoreCase(myName)
                            && msg.recipient().equalsIgnoreCase(currentRecipient);


                    if (isIncomingFromActive || isOutgoingToActive) {
                        String label = msg.sender().equalsIgnoreCase(myName)
                                ? " [YOU]: "
                                : " [" + msg.sender().toUpperCase() + "]: ";
                        messageArea.getItems().add(new String[]{label + msg.content(), msg.timestamp()});
                        messageArea.scrollTo(messageArea.getItems().size() - 1);
                    }
                }
            });
        });

        refreshChat();
    }

    private void loadHistoryFromDB(String targetUser) {
        String myName = messageService.getUsername();

        Platform.runLater(() -> {
            messageArea.getItems().clear();
            messageArea.setCellFactory(lv -> new MessageCell());

            List<DecryptedMessage> localHistory = DatabaseManager.getMessagesForUser(targetUser, myName);

            for (DecryptedMessage msg : localHistory) {
                boolean isMe = msg.sender().equalsIgnoreCase(myName);
                String prefix = isMe ? " [YOU]: " : " [" + msg.sender().toUpperCase() + "]: ";
                String time = msg.timestamp();
                messageArea.getItems().add(new String[]{prefix + msg.content(), formatTimestamp(time)});
            }

            if (!messageArea.getItems().isEmpty()) {
                messageArea.scrollTo(messageArea.getItems().size() - 1);
            }
        });
    }

    private void refreshChat() {
        new Thread(() -> {
            try {
                List<DecryptedMessage> remoteHistory = messageService.fetchAndDecryptHistory(keyManagementService);

                for (DecryptedMessage m : remoteHistory) {
                    DatabaseManager.saveMessage(m.sender(), m.recipient(), m.content(), m.timestamp());
                }

                this.fullHistory = remoteHistory;

                Platform.runLater(() -> {
                    updateContactList();
                    if (currentRecipient != null) {
                        loadHistoryFromDB(currentRecipient);
                    }
                });
            } catch (Exception e) {
                System.err.println("Sync error: " + e.getMessage());
            }
        }).start();
    }

    private void switchChat(String targetUser) {
        this.currentRecipient = targetUser;
        activeChatLabel.setText("SECURE CHANNEL // " + targetUser.toUpperCase());
        loadHistoryFromDB(targetUser);
    }

    private void updateContactList() {
        Set<String> contacts = new HashSet<>();
        String myName = messageService.getUsername();

        List<String> knownSenders = DatabaseManager.getAllContactNames();
        contacts.addAll(knownSenders);

        contacts.removeIf(name -> name.equalsIgnoreCase(myName) || name.equalsIgnoreCase("YOU"));

        Platform.runLater(() -> {
            for (String c : contacts) {
                if (!contactList.getItems().contains(c)) {
                    contactList.getItems().add(c);
                }
            }
        });
    }

    private void initLocalDatabase(String username) {
        try {
            com.example.client.util.DatabaseManager.init(username);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @FXML
    private void handleNewChat() {
        TextField inlineInput = new TextField();
        inlineInput.setPromptText("ENTER NODE ID...");
        inlineInput.setStyle(
                "-fx-background-color: transparent; -fx-text-fill: white;" +
                        "-fx-prompt-text-fill: #444; -fx-border-color: #333;" +
                        "-fx-border-width: 0 0 1 0; -fx-font-family: 'Monospaced';" +
                        "-fx-padding: 14 15;"
        );

        newChannelContainer.getChildren().setAll(inlineInput);
        inlineInput.requestFocus();

        Runnable commit = () -> {
            String id = inlineInput.getText().trim();
            if (!id.isEmpty() && !contactList.getItems().contains(id)) {
                contactList.getItems().add(id);
            }
            if (!id.isEmpty()) {
                contactList.getSelectionModel().select(id);
            }
            newChannelContainer.getChildren().setAll(newChannelBtn);
        };

        inlineInput.setOnAction(e -> commit.run());

        // Cancel on Escape or focus loss
        inlineInput.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                newChannelContainer.getChildren().setAll(newChannelBtn);
            }
        });

        inlineInput.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
            if (!isNowFocused) {
                newChannelContainer.getChildren().setAll(newChannelBtn);
            }
        });
    }

    @FXML
    private void handleOpenSettings() {
        try {
            SettingsController settingsController = ViewSwitcher.switchTo("/fxml/Settings.fxml");
            settingsController.initSettings(
                    messageService.getUsername(), identity, passHash, authClient, keyManagementService
            );
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @FXML
    private void handleSendMessage() {
        String recipientAtTimeOfSend = currentRecipient;
        String sender = messageService.getUsername();
        String text = messageInput.getText().trim();

        if (recipientAtTimeOfSend == null || text.isEmpty()) return;

        new Thread(() -> {
            try {
                KeyBundle bundle = keyManagementService.fetchRecipientKeys(recipientAtTimeOfSend, null);
                messageService.sendMessage(recipientAtTimeOfSend, text, bundle);

                String timestamp = String.valueOf(System.currentTimeMillis());
                DatabaseManager.saveMessage(sender, recipientAtTimeOfSend, text, timestamp);

                Platform.runLater(() -> {
                    if (currentRecipient != null && currentRecipient.equals(recipientAtTimeOfSend)) {
                        messageArea.getItems().add(new String[]{" [YOU]: " + text, timestamp});
                        messageArea.scrollTo(messageArea.getItems().size() - 1);
                    }
                    messageInput.clear();
                });
            } catch (Exception e) {
                Platform.runLater(() -> activeChatLabel.setText("ERROR: SEND FAILED"));
                e.printStackTrace();
            }
        }).start();
    }

    private String formatTimestamp(String timestamp) {
        try {
            long millis = Long.parseLong(timestamp);
            java.time.LocalDateTime dt = java.time.Instant.ofEpochMilli(millis)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDateTime();
            return String.format("%04d/%02d/%02d %02d:%02d:%02d",
                    dt.getYear(),
                    dt.getMonthValue(),
                    dt.getDayOfMonth(),
                    dt.getHour(),
                    dt.getMinute(),
                    dt.getSecond());
        } catch (Exception e) {
            return "";
        }
    }
}

