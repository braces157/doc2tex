package dev.doc2tex;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** A process-lifetime lock is acquired before Spring can open packaged project storage. */
@Component
class PackagedRuntime {
    record Instance(String instanceId, long pid, int port, String token) {}
    private static FileChannel channel;
    private static FileLock lock;
    private static Path home;
    private static final String ID = UUID.randomUUID().toString(), TOKEN = UUID.randomUUID() + "-" + UUID.randomUUID();

    static synchronized void acquire() throws IOException {
        String configured = System.getProperty("doc2tex.home", "");
        if (configured.isBlank()) return;
        home = Path.of(configured).toAbsolutePath().normalize(); checkStartupPath(home); Files.createDirectories(home);
        Path file = home.resolve(".instance.lock"); checkStartupPath(file);
        channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        lock = channel.tryLock();
        if (lock == null) { channel.close(); throw new IOException("doc2TeX is already running for this data folder. Use Launch doc2TeX.cmd to reopen it."); }
        Runtime.getRuntime().addShutdownHook(new Thread(PackagedRuntime::release, "packaged-instance-cleanup"));
    }

    static boolean packaged() { return home != null; }
    static void checkStartupPath(Path path) throws IOException {
        try { JobStore.checkUnlinked(path); }
        catch (ApiException exception) { throw new IOException("Packaged startup refused a linked or unverifiable data path. Choose a normal local DOC2TEX_HOME and retry.", exception); }
    }
    static String id() { return packaged() ? ID : ""; }
    static boolean authorized(String token) {
        return packaged() && token != null && java.security.MessageDigest.isEqual(TOKEN.getBytes(java.nio.charset.StandardCharsets.UTF_8), token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @EventListener
    void listening(WebServerInitializedEvent event) throws IOException {
        if (!packaged()) return;
        Path target = home.resolve("instance.json"), temporary = home.resolve("instance.json.tmp");
        JobStore.checkUnlinked(target); JobStore.checkUnlinked(temporary);
        Files.writeString(temporary, JsonMapper.builder().build().writeValueAsString(new Instance(ID, ProcessHandle.current().pid(), event.getWebServer().getPort(), TOKEN)));
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static synchronized void release() {
        try { if (home != null && lock != null && lock.isValid()) { Files.deleteIfExists(home.resolve("instance.json")); lock.release(); } }
        catch (IOException ignored) { /* A stale instance file is verified against the live API by the launcher. */ }
        finally { try { if (channel != null) channel.close(); } catch (IOException ignored) { } }
    }
}
