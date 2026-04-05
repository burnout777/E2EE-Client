package com.example.client.ui;

import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

public class MessageCell extends ListCell<String[]> {
    private final HBox layout = new HBox();
    private final Label messageLabel = new Label();
    private final Label timeLabel = new Label();
    private final Region spacer = new Region();

    public MessageCell() {
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        timeLabel.setStyle("-fx-font-size: 9px; -fx-padding: 0 10 0 0;");
        timeLabel.getStyleClass().add("dim");
        layout.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        layout.getChildren().addAll(messageLabel, spacer, timeLabel);
        layout.setStyle("-fx-padding: 4 0;");
    }

    @Override
    protected void updateItem(String[] item, boolean empty) {
        super.updateItem(item, empty);
        if (empty || item == null) {
            setGraphic(null);
        } else {
            messageLabel.setText(item[0]);  // message text
            timeLabel.setText(item[1]);     // timestamp
            setGraphic(layout);
        }
    }
}