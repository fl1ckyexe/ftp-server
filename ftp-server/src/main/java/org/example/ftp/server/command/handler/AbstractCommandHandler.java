package org.example.ftp.server.command.handler;

import org.example.ftp.common.protocol.FtpResponse;
import org.example.ftp.common.protocol.Responses;
import org.example.ftp.server.command.FtpCommandHandler;
import org.example.ftp.server.command.visitor.CommandVisitor;
import org.example.ftp.server.fs.log.ServerLogService;
import org.example.ftp.server.session.FtpSession;
import org.example.ftp.server.session.memento.SessionMemento;

public abstract class AbstractCommandHandler implements FtpCommandHandler {

    public final FtpResponse handle(FtpSession session, String commandLine) {

        if (commandLine == null || commandLine.isBlank()) {
            return Responses.emptyCommand();
        }

        log(session, commandLine);

        SessionMemento snapshot = session.save();

        if (!checkState(session)) {
            return notAllowed();
        }

        try {
            return execute(session, extractArgument(commandLine));
        } catch (Exception e) {
            session.restore(snapshot);
            return Responses.requestedActionAbortedLocalError();
        }
    }

    protected void log(FtpSession session, String commandLine) {
        String safeCmd = commandLine == null ? "" : commandLine.trim();
        // Never log plaintext passwords
        if (safeCmd.regionMatches(true, 0, "PASS", 0, 4)) {
            safeCmd = "PASS ******";
        }

        String safeUser =
                session == null
                        ? "unknown-session"
                        : session.getUsername() != null
                        ? session.getUsername()
                        : session.getPendingUsername() != null
                        ? session.getPendingUsername()
                        : "anonymous";

        ServerLogService.log(safeUser + " >> " + safeCmd);
    }

    @Override
    public void accept(CommandVisitor visitor) {
        visitor.visit(this);
    }

    protected String getStateKey() {
        return getCommandName();
    }

    protected boolean checkState(FtpSession session) {
        return session.getState().canExecute(getStateKey());
    }

    public abstract String getCommandName();

    protected abstract FtpResponse execute(FtpSession session, String argument);

    protected abstract FtpResponse notAllowed();

    protected String extractArgument(String commandLine) {
        int idx = commandLine.indexOf(' ');
        return idx > 0 ? commandLine.substring(idx + 1) : null;
    }
}