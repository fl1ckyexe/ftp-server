package org.example.ftp.server.command.visitor;

import org.example.ftp.server.command.handler.AbstractCommandHandler;
import org.example.ftp.server.fs.log.ServerLogService;

public class LoggingVisitor implements CommandVisitor {

    @Override
    public void visit(AbstractCommandHandler handler) {
        ServerLogService.log(
                "Command executed: " + handler.getCommandName()
        );
    }
}
