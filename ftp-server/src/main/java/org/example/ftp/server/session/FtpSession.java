package org.example.ftp.server.session;

import org.example.ftp.common.protocol.FtpResponse;
import org.example.ftp.server.auth.AuthService;
import org.example.ftp.server.auth.PermissionService;
import org.example.ftp.server.auth.db.SqliteFolderPermissionRepository;
import org.example.ftp.server.auth.db.SqliteFolderRepository;
import org.example.ftp.server.auth.db.SqliteSharedFolderRepository;
import org.example.ftp.server.auth.db.SqliteUserRepository;
import org.example.ftp.server.command.handler.CommandDispatcher;
import org.example.ftp.server.connection.ConnectionLimiter;
import org.example.ftp.server.session.memento.SessionMemento;
import org.example.ftp.server.stats.StatsService;
import org.example.ftp.server.transfer.RateLimiter;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;

public class FtpSession {

    private String pendingUsername;
    private String username;
    private boolean authenticated;

    private final AuthService authService;
    private final PermissionService permissionService;
    private final StatsService statsService;
    
    private final SqliteUserRepository userRepository;
    private final SqliteFolderRepository folderRepository;
    private final SqliteFolderPermissionRepository folderPermissionRepository;
    private final SqliteSharedFolderRepository sharedFolderRepository;

    private final Path ftpRoot;
    private final Path sharedDirectory;

    private Path homeDirectory;
    private Path currentDirectory;
    private boolean hasExplicitlyChangedDirectory = false; // Флаг, что директория была явно изменена через CWD

    private ServerSocket passiveDataSocket;

    private SessionState state;
    private final PrintWriter writer;
    private final CommandDispatcher dispatcher = new CommandDispatcher();

    private final ConnectionLimiter connectionLimiter;
    private final RateLimiter globalUploadRateLimiter;
    private final RateLimiter globalDownloadRateLimiter;
    private RateLimiter uploadRateLimiter;
    private RateLimiter downloadRateLimiter;
    private boolean usesGlobalUploadLimit;
    private boolean usesGlobalDownloadLimit;

    private volatile boolean closeRequested;

    // ===== transfer abort (ABOR) =====
    private volatile boolean transferAbortRequested;
    private volatile Socket activeDataConnection;
    private volatile Thread activeTransferThread;

    public FtpSession(
            PrintWriter writer,
            Path ftpRoot,
            AuthService authService,
            PermissionService permissionService,
            StatsService statsService,
            ConnectionLimiter connectionLimiter,
            RateLimiter globalUploadRateLimiter,
            RateLimiter globalDownloadRateLimiter,
            SqliteUserRepository userRepository,
            SqliteFolderRepository folderRepository,
            SqliteFolderPermissionRepository folderPermissionRepository,
            SqliteSharedFolderRepository sharedFolderRepository
    ) {
        this.writer = writer;
        this.ftpRoot = ftpRoot;
        this.authService = authService;
        this.permissionService = permissionService;
        this.statsService = statsService;
        this.connectionLimiter = connectionLimiter;
        this.globalUploadRateLimiter = globalUploadRateLimiter;
        this.globalDownloadRateLimiter = globalDownloadRateLimiter;
        this.userRepository = userRepository;
        this.folderRepository = folderRepository;
        this.folderPermissionRepository = folderPermissionRepository;
        this.sharedFolderRepository = sharedFolderRepository;

        this.state = new UnauthenticatedState();
        this.authenticated = false;

        try {
            this.sharedDirectory = ftpRoot.resolve("shared").normalize().toAbsolutePath();
            Files.createDirectories(this.sharedDirectory);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to init shared directory", e);
        }
    }

    public void handle(String commandLine) {
        FtpResponse response = dispatcher.dispatch(this, commandLine);
        writer.print(response.toProtocolString());
        writer.flush();
    }
    
    public void sendResponse(FtpResponse response) {
        writer.print(response.toProtocolString());
        writer.flush();
    }

    public void authenticate(String username) {
        this.username = username;
        this.pendingUsername = null;
        this.authenticated = true;
        this.state = new AuthenticatedState();

        try {
            homeDirectory = ftpRoot.resolve("users").resolve(username).normalize().toAbsolutePath();
            Files.createDirectories(homeDirectory);
            currentDirectory = homeDirectory;

        
            Long legacyRateLimit = authService.getRateLimit(username);
            Long userUpload = authService.getUploadSpeed(username);
            Long userDownload = authService.getDownloadSpeed(username);

            long effectiveUpload;
            if (userUpload != null && userUpload > 0) {
                effectiveUpload = userUpload;
                usesGlobalUploadLimit = false;
                System.out.println("FtpSession.authenticate() - Upload limiter for " + username + ": user upload_speed=" + effectiveUpload + " bytes/s");
            } else if (legacyRateLimit != null && legacyRateLimit > 0) {
                effectiveUpload = legacyRateLimit;
                usesGlobalUploadLimit = false;
                System.out.println("FtpSession.authenticate() - Upload limiter for " + username + ": legacy rate_limit=" + effectiveUpload + " bytes/s");
            } else {
                effectiveUpload = globalUploadRateLimiter.getLimit();
                usesGlobalUploadLimit = true;
                System.out.println("FtpSession.authenticate() - Upload limiter for " + username + ": global upload limit=" + effectiveUpload + " bytes/s");
            }

            long effectiveDownload;
            if (userDownload != null && userDownload > 0) {
                effectiveDownload = userDownload;
                usesGlobalDownloadLimit = false;
                System.out.println("FtpSession.authenticate() - Download limiter for " + username + ": user download_speed=" + effectiveDownload + " bytes/s");
            } else if (legacyRateLimit != null && legacyRateLimit > 0) {
                effectiveDownload = legacyRateLimit;
                usesGlobalDownloadLimit = false;
                System.out.println("FtpSession.authenticate() - Download limiter for " + username + ": legacy rate_limit=" + effectiveDownload + " bytes/s");
            } else {
                effectiveDownload = globalDownloadRateLimiter.getLimit();
                usesGlobalDownloadLimit = true;
                System.out.println("FtpSession.authenticate() - Download limiter for " + username + ": global download limit=" + effectiveDownload + " bytes/s");
            }

            uploadRateLimiter = new RateLimiter(effectiveUpload);
            downloadRateLimiter = new RateLimiter(effectiveDownload);

        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Backwards compatibility: returns the download limiter.
     */
    public RateLimiter getEffectiveRateLimiter() {
        return getDownloadRateLimiter();
    }

    public RateLimiter getUploadRateLimiter() {
        if (uploadRateLimiter == null) return null;
        if (usesGlobalUploadLimit) {
            uploadRateLimiter.setLimit(globalUploadRateLimiter.getLimit());
        }
        return uploadRateLimiter;
    }

    public RateLimiter getDownloadRateLimiter() {
        if (downloadRateLimiter == null) return null;
        if (usesGlobalDownloadLimit) {
            downloadRateLimiter.setLimit(globalDownloadRateLimiter.getLimit());
        }
        return downloadRateLimiter;
    }

    public void requestClose() {
        this.closeRequested = true;
    }

    public void requestTransferAbort() {
        this.transferAbortRequested = true;

        // Force-close active data connection so STOR/RETR unblocks immediately
        Socket s = this.activeDataConnection;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {}
        }

        // Also close passive listener socket
        try {
            closePassiveDataSocket();
        } catch (IOException ignored) {}

        // Best-effort: interrupt transfer thread (helps break out of limiter sleeps quickly)
        Thread t = this.activeTransferThread;
        if (t != null) {
            t.interrupt();
        }
    }

    public boolean isTransferAbortRequested() {
        return transferAbortRequested;
    }

    public void clearTransferAbort() {
        this.transferAbortRequested = false;
    }

    public void setActiveDataConnection(Socket socket) {
        this.activeDataConnection = socket;
    }

    public void clearActiveDataConnection(Socket socket) {
        if (this.activeDataConnection == socket) {
            this.activeDataConnection = null;
        }
    }

    public void setActiveTransferThread(Thread t) {
        this.activeTransferThread = t;
    }

    public void clearActiveTransferThread(Thread t) {
        if (this.activeTransferThread == t) {
            this.activeTransferThread = null;
        }
    }

    public boolean isCloseRequested() {
        return closeRequested;
    }

    // ===== getters / setters =====

    public boolean isAuthenticated() { return authenticated; }

    public String getUsername() { return username; }

    public void setPendingUsername(String u) { pendingUsername = u; }

    public String getPendingUsername() { return pendingUsername; }

    public AuthService getAuthService() { return authService; }

    public PermissionService getPermissionService() { return permissionService; }

    public StatsService getStatsService() { return statsService; }

    public ConnectionLimiter getConnectionLimiter() { return connectionLimiter; }

    public Path getSharedDirectory() { return sharedDirectory; }
    
    public Path getFtpRoot() { return ftpRoot; }

    public Path getHomeDirectory() { return homeDirectory; }

    public Path getCurrentDirectory() { return currentDirectory; }

    public void setCurrentDirectory(Path p) { currentDirectory = p; }
    
    public void markDirectoryChanged() { hasExplicitlyChangedDirectory = true; }
    
    public void resetDirectoryChangeFlag() { hasExplicitlyChangedDirectory = false; }
    
    public boolean hasExplicitlyChangedDirectory() { return hasExplicitlyChangedDirectory; }

    public ServerSocket getPassiveDataSocket() { return passiveDataSocket; }

    public void openPassiveDataSocket() throws IOException {
        // Close previous passive socket (client may issue PASV multiple times)
        try {
            closePassiveDataSocket();
        } catch (IOException ignored) {}

        passiveDataSocket = new ServerSocket(0);
        passiveDataSocket.setReuseAddress(true);
        // Avoid indefinite hangs: data connection must arrive within timeout
        try {
            passiveDataSocket.setSoTimeout(Integer.getInteger("ftp.data.timeoutMs", 15000));
        } catch (Exception ignored) {}
    }

    public void closePassiveDataSocket() throws IOException {
        if (passiveDataSocket != null) {
            passiveDataSocket.close();
            passiveDataSocket = null;
        }
    }

    public SessionState getState() { return state; }

    public void setState(SessionState s) { state = s; }

    public SqliteUserRepository getUserRepository() { return userRepository; }
    public SqliteFolderRepository getFolderRepository() { return folderRepository; }
    public SqliteFolderPermissionRepository getFolderPermissionRepository() { return folderPermissionRepository; }
    public SqliteSharedFolderRepository getSharedFolderRepository() { return sharedFolderRepository; }

    // ===== memento =====

    public SessionMemento save() {
        return new SessionMemento(
                state,
                pendingUsername,
                username,
                authenticated,
                currentDirectory,
                homeDirectory
        );
    }

    public void restore(SessionMemento m) {
        state = m.getState();
        pendingUsername = m.getPendingUsername();
        username = m.getUsername();
        authenticated = m.isAuthenticated();
        currentDirectory = m.getCurrentDirectory();
        homeDirectory = m.getHomeDirectory();
    }
}