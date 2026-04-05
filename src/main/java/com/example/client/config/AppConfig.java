package com.example.client.config;

import java.io.InputStream;
import java.util.Properties;
import java.io.File;
import java.nio.file.Paths;

public class AppConfig {
    public static final String APP_HOME = Paths.get(System.getProperty("user.home"), ".securecomm").toString();
    public static final String KEYS_DIR = Paths.get(APP_HOME, "keys").toString();
    public static final String VAULTS_DIR = Paths.get(APP_HOME, "vaults").toString();

    public static void initFolders() {
        try {
            new File(KEYS_DIR).mkdirs();
            new File(VAULTS_DIR).mkdirs();
            System.out.println("Storage initialized at: " + APP_HOME);
        } catch (Exception e) {
            System.err.println("CRITICAL: File system error: " + e.getMessage());
        }
    }
    private static final Properties props = new Properties();

    static {
        try (InputStream input = AppConfig.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (input == null) {
                System.err.println("Sorry, unable to find application.properties");
            } else {
                props.load(input);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public static String getProperty(String key, String defaultValue) {
        return props.getProperty(key, defaultValue);
    }

    public static String getBaseUrl() {
        return getProperty("server.url", "http://localhost:8080") + "/api/users";
    }

    public static String getMessageUrl() {
        return getProperty("server.url", "http://localhost:8080") + "/api/messages";
    }
}