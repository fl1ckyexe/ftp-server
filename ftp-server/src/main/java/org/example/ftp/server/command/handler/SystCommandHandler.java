package org.example.ftp.server.command.handler;

import org.example.ftp.common.protocol.FtpResponse;
import org.example.ftp.common.protocol.Responses;
import org.example.ftp.server.session.FtpSession;

public class SystCommandHandler extends AbstractCommandHandler {

    @Override
    public String getCommandName() {
        return "SYST";
    }

    @Override
    protected boolean checkState(FtpSession session) {
        return super.checkState(session);
    }

    @Override
    protected FtpResponse execute(FtpSession session, String argument) {
        // Возвращаем стандартный ответ для Unix-подобных систем
        // Apache Commons Net ожидает ответ в формате "215 UNIX Type: L8"
        return Responses.ok(215, "UNIX Type: L8");
    }

    @Override
    protected FtpResponse notAllowed() {
        return Responses.needLogin();
    }
}

