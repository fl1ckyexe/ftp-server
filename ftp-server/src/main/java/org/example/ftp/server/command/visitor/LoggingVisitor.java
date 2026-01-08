package org.example.ftp.server.command.visitor;

import org.example.ftp.server.command.handler.AbstractCommandHandler;
import org.example.ftp.server.util.DebugLog;

public class LoggingVisitor implements CommandVisitor {

    @Override
    public void visit(AbstractCommandHandler handler) {
        // Keep visitor for coursework (Visitor pattern), but make it debug-only to avoid heavy log spam.
        DebugLog.d("Command executed: " + handler.getCommandName());
    }
}
