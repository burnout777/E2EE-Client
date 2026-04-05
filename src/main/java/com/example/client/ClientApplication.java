package com.example.client;

import com.example.client.config.AppConfig;
import com.example.client.ui.ViewSwitcher;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class ClientApplication extends Application {

    @Override
    public void start(Stage primaryStage) throws Exception {
        primaryStage.setFullScreen(true);
        AppConfig.initFolders();
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/Login.fxml"));
        Parent root = loader.load();

        Scene scene = new Scene(root);
        ViewSwitcher.setScene(scene);

        primaryStage.setScene(scene);

        primaryStage.setFullScreenExitHint("");

        primaryStage.show();

        javafx.application.Platform.runLater(() -> {
            primaryStage.setFullScreen(true);
        });
    }
}