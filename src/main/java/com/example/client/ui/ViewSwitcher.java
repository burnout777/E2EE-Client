package com.example.client.ui;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import java.io.IOException;

public class ViewSwitcher {
    private static Scene scene;

    public static void setScene(Scene scene) {
        ViewSwitcher.scene = scene;
    }

    public static <T> T switchTo(String fxmlPath) throws IOException {
        FXMLLoader loader = new FXMLLoader(ViewSwitcher.class.getResource(fxmlPath));
        Parent root = loader.load();
        scene.setRoot(root);
        return loader.getController();
    }
}