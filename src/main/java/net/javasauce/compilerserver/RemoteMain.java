package net.javasauce.compilerserver;

import net.javasauce.compilerserver.packet.CompileRequestPacket;
import net.javasauce.compilerserver.packet.CompileResultPacket;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Main entrypoint for out-of-process, server-like Javac.
 * <p>
 * Created by covers1624 on 8/29/25.
 */
public class RemoteMain {

    private static final boolean DEBUG = Boolean.getBoolean("net.javasauce.RemoteCompiler.debug");
    private static final PrintStream logger = System.err;

    private final ExecutorService compileExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors(), new ThreadFactory() {
        final AtomicInteger num = new AtomicInteger();

        @Override
        public Thread newThread(@NotNull Runnable r) {
            Thread thread = new Thread(r);
            thread.setName("Compile Thread " + num.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    });
    private final ObjectOutputStream out;
    private final SaferObjectInputStream in;
    private final Compiler compiler;

    private transient boolean running = true;

    public static void main(String[] args) {
        logger.println("Starting RemoteCompiler.");
        logger.println("Using vm at " + System.getProperty("java.home"));
        logger.println("   Java Version " + System.getProperty("java.version"));
        logger.println("   Java Vendor  " + System.getProperty("java.vendor"));
        logger.println("   OS           " + System.getProperty("os.name"));
        logger.println("   OS Arch      " + System.getProperty("os.arch"));
        logger.println("   OS Version   " + System.getProperty("os.version"));
        try {
            new RemoteMain(args).run();
        } catch (Throwable ex) {
            logger.println("Fatal error.");
            ex.printStackTrace(logger);
        }
    }

    public RemoteMain(String[] args) throws IOException {
        compiler = Compiler.forLocal(Stream.of(args)
                .map(Paths::get)
                .collect(Collectors.toList()));
        out = new ObjectOutputStream(System.out);
        out.flush();
        in = new SaferObjectInputStream(System.in);
        logger.println("RemoteCompiler ready for commands.");
    }

    private synchronized void writePacket(Object packet) {
        try {
            out.writeObject(packet);
            out.flush();
        } catch (IOException ex) {
            logger.println("Error writing packet.");
            ex.printStackTrace(logger);
        }
    }

    private void run() throws IOException, ClassNotFoundException {
        while (running) {
            Object packet = in.readObject();
            if (packet instanceof CompileRequestPacket) {
                handleCompileRequest((CompileRequestPacket) packet);
            } else {
                logger.println("Unknown packet: " + packet.getClass().getName());
                stop();
            }
        }
    }

    private void stop() {
        running = false;
        try {
            in.close();
        } catch (IOException e) {
            logger.println("Error closing stdin");
            e.printStackTrace(logger);
        }
    }

    private void handleCompileRequest(CompileRequestPacket packet) {
        if (DEBUG) logger.println("Received request " + packet.id + " for " + packet.sourceUri);
        compileExecutor.submit(() -> {
            if (DEBUG) logger.println("Executing request " + packet.id + " on thread " + Thread.currentThread().getName());
            try {
                Compiler.CompileResult result = compiler.compile(
                        packet.sourceUri,
                        packet.source,
                        packet.compilerArgs
                );
                writePacket(new CompileResultPacket(packet.id, result));
            } catch (Throwable ex) {
                logger.println("Compiler crash!");
                ex.printStackTrace(logger);
                stop();
            }
        });
    }
}
