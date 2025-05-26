package com.example.util;

import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class LogUtil {
    private static final String LOG_FILE_PATH = "src\\main\\resources\\pbft_consensus.log";
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void logMessage(String message) {
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(LOG_FILE_PATH, true), "UTF-8"))) {
            String timestamp = LocalDateTime.now().format(formatter);
            writer.write(timestamp + " - " + message + "\n");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static boolean checkMessagesInPhase(String phase, String expectedMessage) {
        List<String> messagesInPhase = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader("src\\main\\resources\\pbft_consensus.log"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains(phase)) {
                    int startIndex = line.indexOf("{");
                    if (startIndex != -1) {
                        messagesInPhase.add(line.substring(startIndex));
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        for (String message : messagesInPhase) {
            if (!message.equals(expectedMessage)) {
                return false;
            }
        }
        return true;
    }
}