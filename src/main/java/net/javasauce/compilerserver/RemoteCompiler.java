package net.javasauce.compilerserver;

import net.javasauce.compilerserver.packet.CompileRequestPacket;
import net.javasauce.compilerserver.packet.CompileResultPacket;
import net.javasauce.compilerserver.util.SaferObjectInputStream;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Created by covers1624 on 8/29/25.
 */
class RemoteCompiler implements Compiler {

    private static final Logger LOGGER = LoggerFactory.getLogger(RemoteCompiler.class);

    private static final String OVERRIDE_PATH = System.getProperty("net.javasauce.RemoteCompiler.jar_Path");
    private static final boolean DEBUG = Boolean.getBoolean("net.javasauce.RemoteCompiler.debug");

    private final Map<UUID, CompletableFuture<CompileResult>> pending = new ConcurrentHashMap<>();

    private final Process process;
    private final ObjectOutputStream out;
    private final SaferObjectInputStream in;

    private final Thread readThread;
    private final Thread logThread;

    private boolean exitRequested;

    public RemoteCompiler(Path javaExecutable, List<Path> compileClasspath) throws IOException {
        Path ourJarPath = getOurPath();
        if (OVERRIDE_PATH != null) {
            ourJarPath = Paths.get(OVERRIDE_PATH);
        }
        if (ourJarPath == null) {
            throw new RuntimeException("Unable to locate our own jar on the classpath. Please ensure it not shadowed, or provide the RemoteCompiler.jar_path sysprop");
        }

        List<String> args = new ArrayList<>(Arrays.asList(
                javaExecutable.toAbsolutePath().toString(),
                "-ea",
                "-Dnet.javasauce.CompileServer.compile_classpath=" + compileClasspath.stream()
                        .map(e -> e.toAbsolutePath().toString())
                        .collect(Collectors.joining(File.pathSeparator))
        ));
        if (DEBUG) {
            args.add("-Dnet.javasauce.RemoteCompiler.debug=true");
        }

        args.add("-cp");
        args.add(ourJarPath.toAbsolutePath().toString());
        args.add(RemoteMain.class.getName());

        ProcessBuilder builder = new ProcessBuilder(args);
        LOGGER.info("Starting Java compiler on vm {}", javaExecutable.toAbsolutePath());

        process = builder.start();

        logThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    LOGGER.info("CompilerServer: {}", line);
                }
            } catch (Throwable ignored) {
            }
        });
        logThread.setName("RemoteCompiler Log");
        logThread.setDaemon(true);
        logThread.start();

        LOGGER.info("RemoteCompiler started!");

        LOGGER.info("Negotiating..");
        in = new SaferObjectInputStream(process.getInputStream());
        out = new ObjectOutputStream(process.getOutputStream());
        out.flush();

        readThread = new Thread(() -> {
            try {
                while (process.isAlive()) {
                    Object packet = in.readObject();
                    if (packet instanceof CompileResultPacket) {
                        handleCompileResult((CompileResultPacket) packet);
                    } else {
                        throw new RuntimeException("Unknown packet: " + packet.getClass().getName());
                    }
                }
            } catch (WriteAbortedException | EOFException ex) {
                if (exitRequested) return;
                LOGGER.error("RemoteCompiler quit unexpectedly.");
                stop();
            } catch (Throwable ex) {
                System.err.println("Error on read thread.");
                ex.printStackTrace(System.err);
                stop();
            }
        });
        readThread.setName("RemoteCompiler Read");
        readThread.setDaemon(true);
        readThread.start();
        LOGGER.info("Finished negotiating, ready.");
    }

    private synchronized void writePacket(Object obj) throws IOException {
        out.writeObject(obj);
        out.flush();
    }

    private void handleCompileResult(CompileResultPacket packet) {
        CompletableFuture<CompileResult> result = pending.get(packet.id);
        if (result == null) {
            throw new RuntimeException("CompletableFuture has gone missing??");
        }

        result.complete(packet.result);
    }

    @Override
    public CompileResult compile(URI sourceUri, String source, List<String> extraJavacArgs) {
        if (!process.isAlive()) throw new RuntimeException("CompilerServer is dead.");

        UUID id = UUID.randomUUID();
        CompletableFuture<CompileResult> result = new CompletableFuture<>();
        pending.put(id, result);

        try {
            writePacket(new CompileRequestPacket(
                    id,
                    sourceUri,
                    source,
                    extraJavacArgs
            ));
        } catch (IOException ex) {
            pending.remove(id);
            throw new RuntimeException("Failed to communicate with CompilerServer.", ex);
        }

        return result.join();
    }

    private void stop() {
        exitRequested = true;
        LOGGER.info("Stopping RemoteCompiler.");
        process.destroy();
        for (CompletableFuture<CompileResult> value : pending.values()) {
            value.completeExceptionally(new RuntimeException("RemoteCompiler quit unexpectedly."));
        }
        try {
            process.waitFor();
            readThread.join();
            logThread.join();
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted waiting for compiler and threads to stop.", e);
        }
    }

    @Override
    public void close() {
        stop();
    }

    private static @Nullable Path getOurPath() {
        ProtectionDomain dom = RemoteMain.class.getProtectionDomain();
        if (dom == null) return null;

        CodeSource src = dom.getCodeSource();
        if (src == null) return null;

        try {
            return Paths.get(src.getLocation().toURI());
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
