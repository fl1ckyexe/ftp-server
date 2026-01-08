package org.example.ftp.server.session;

/**
 * After successful USER, server expects PASS (or USER again to change username).
 * This models the FTP login flow explicitly (State pattern).
 */
public class AwaitingPasswordState implements SessionState {

    @Override
    public boolean canExecute(String command) {
        return command.equals("PASS")
                || command.equals("USER")
                || command.equals("QUIT")
                || command.equals("NOOP")
                || command.equals("FEAT")
                || command.equals("OPTS")
                || command.equals("PWD");
    }
}


