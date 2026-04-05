package com.example.client.util;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import com.example.client.dto.DecryptedMessage;

public class DatabaseManager {
    private static Connection conn;
    private static String dbUrl; // Store this for reconnection if needed

    public static void init(String username) {
        dbUrl = "jdbc:sqlite:" + System.getProperty("user.home") + "/.securecomm/" + username + ".db";
        try {
            if (conn == null || conn.isClosed()) {
                conn = DriverManager.getConnection(dbUrl);
            }

            String sql = "CREATE TABLE IF NOT EXISTS messages (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "sender TEXT, " +
                    "recipient TEXT, " +
                    "content TEXT, " +
                    "timestamp TEXT" +
                    ");";
            conn.createStatement().execute(sql);
            System.out.println(">>> Database connected and initialized.");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private static Connection getConnection() throws SQLException {
        if (conn == null || conn.isClosed()) {
            if (dbUrl == null) throw new SQLException("Database not initialized. Call init() first.");
            conn = DriverManager.getConnection(dbUrl);
        }
        return conn;
    }

    public static boolean saveMessage(String sender, String recipient, String content, String timestamp) {
        String checkSql = "SELECT COUNT(*) FROM messages WHERE sender = ? AND recipient = ? AND content = ? AND timestamp = ?";
        String insertSql = "INSERT INTO messages(sender, recipient, content, timestamp) VALUES(?,?,?,?)";

        try (Connection conn = getConnection()) {
            try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
                checkStmt.setString(1, sender);
                checkStmt.setString(2, recipient);
                checkStmt.setString(3, content);
                checkStmt.setString(4, timestamp);

                try (ResultSet rs = checkStmt.executeQuery()) {
                    if (rs.next() && rs.getInt(1) > 0) {
                        return false;
                    }
                }
            }

            try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                insertStmt.setString(1, sender);
                insertStmt.setString(2, recipient);
                insertStmt.setString(3, content);
                insertStmt.setString(4, timestamp);
                insertStmt.executeUpdate();
                return true;
            }
        } catch (SQLException e) {
            System.err.println("Database Error: " + e.getMessage());
            return false;
        }
    }

    public static List<DecryptedMessage> getMessagesForUser(String targetUser, String myName) {
        List<DecryptedMessage> history = new ArrayList<>();
        String sql = "SELECT * FROM messages WHERE (sender = ? AND recipient = ?) " +
                "OR (sender = ? AND recipient = ?) ORDER BY timestamp ASC";

        try (
                Connection conn = getConnection();
                PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, targetUser);
            pstmt.setString(2, myName);
            pstmt.setString(3, myName);
            pstmt.setString(4, targetUser);

            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                history.add(new DecryptedMessage(
                        rs.getString("sender"),
                        rs.getString("recipient"),
                        rs.getString("content"),
                        rs.getString("timestamp"),
                        true
                ));
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return history;
    }

    public static List<String> getAllContactNames() {
        List<String> contacts = new ArrayList<>();
        String sql = "SELECT DISTINCT sender FROM messages UNION SELECT DISTINCT recipient FROM messages";

        try (
                Connection conn = getConnection();
                PreparedStatement pstmt = conn.prepareStatement(sql);
                ResultSet rs = pstmt.executeQuery()) {

            while (rs.next()) {
                String name = rs.getString(1);
                if (name != null && !name.equalsIgnoreCase("YOU")) {
                    contacts.add(name);
                }
            }
        } catch (SQLException e) {
            System.err.println("Error fetching contacts: " + e.getMessage());
        }
        return contacts;
    }
}