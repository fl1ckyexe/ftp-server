package org.example.ftp.server.command.handler;

import org.example.ftp.common.protocol.FtpResponse;
import org.example.ftp.common.protocol.Responses;
import org.example.ftp.server.session.FtpSession;

/**
 * ABOR - abort current data transfer (upload/download).
 */
public class AborCommandHandler extends AbstractCommandHandler {

    @Override
    public String getCommandName() {
        return "ABOR";
    }

    @Override
    protected FtpResponse execute(FtpSession session, String argument) {
        // Mark transfer as aborted and force-close data sockets to unblock STOR/RETR
        session.requestTransferAbort();
        return Responses.ok(226, "Abort successful.");
    }

    @Override
    protected FtpResponse notAllowed() {
        // ABOR is safe to accept even if not logged in
        return Responses.ok(226, "Abort successful.");
    }
}


