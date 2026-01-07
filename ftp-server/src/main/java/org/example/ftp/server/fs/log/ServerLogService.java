package org.example.ftp.server.fs.log;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class ServerLogService {

    private static final List<String> LOGS = new ArrayList<>();

    public static synchronized void log(String message) {
        String line = "[" + LocalDateTime.now() + "] " + message;

        System.out.println(line);

        LOGS.add(line);

    }

    public static synchronized List<String> getLogs() {
        return new ArrayList<>(LOGS); // копия!
    }
}
