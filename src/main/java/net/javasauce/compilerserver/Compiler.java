package net.javasauce.compilerserver;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * An abstract interface over Java's compiler tooling interface.
 * <p>
 * It supports running a compiler as a remote process, like a server.
 * <p>
 * Created by covers1624 on 8/29/25.
 */
public interface Compiler extends AutoCloseable {

    /**
     * Start a remote Java compiler server.
     *
     * @param javaExecutable   The Java executable of the jdk to use. Must have a compiler present.
     * @param compileClasspath The compile classpath to use.
     * @return The Compiler.
     */
    static Compiler of(Path javaExecutable, List<Path> compileClasspath) throws IOException {
        return new RemoteCompiler(javaExecutable, compileClasspath);
    }

    /**
     * Create a wrapper around the Java compiler on the current jdk.
     *
     * @param compileClasspath The compile classpath to use.
     * @return The Compiler.
     */
    static Compiler forLocal(List<Path> compileClasspath) throws IOException {
        return new LocalCompiler(compileClasspath);
    }

    /**
     * Request a compilation unit be compiled.
     *
     * @param sourceUri      The URI describing the location of the source file. Javac
     *                       expects that packages are present in this URI.
     * @param source         The source content for the compilation unit being compiled.
     * @param extraJavacArgs Any additional Java arguments to provide.
     * @return The result.
     */
    CompileResult compile(URI sourceUri, String source, List<String> extraJavacArgs);

    /**
     * Release any resources and stop any sub-processes.
     */
    @Override
    void close() throws IOException;

    /**
     * The result of a compile operation.
     */
    class CompileResult implements Serializable {

        /**
         * The compiler output for the compilation unit.
         * <p>
         * Each entry is in the form of relative paths, using forward slashes. E.g: {@code my/package/MyClass.class}
         */
        public final Map<String, byte[]> output;
        /**
         * If the operation was a success.
         */
        public final boolean success;
        /**
         * The compiler log.
         */
        public final String compileLog;
        /**
         * Contains a compiler crash exception, if the compiler crashed.
         */
        public final @Nullable Throwable javacCrash;

        public CompileResult(Map<String, byte[]> output, boolean success, String compileLog, @Nullable Throwable javacCrash) {
            this.output = output;
            this.success = success;
            this.compileLog = compileLog;
            this.javacCrash = javacCrash;
        }
    }
}
