package net.javasauce.compilerserver;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
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
    static Compiler of(Path javaExecutable, Collection<Path> compileClasspath) throws IOException {
        return of(javaExecutable, Collections.emptyList(), compileClasspath);
    }

    /**
     * Start a remote Java compiler server.
     *
     * @param javaExecutable   The Java executable of the jdk to use. Must have a compiler present.
     * @param jvmArgs          Any additional JVM arguments.
     * @param compileClasspath The compile classpath to use.
     * @return The Compiler.
     */
    static Compiler of(Path javaExecutable, List<String> jvmArgs, Collection<Path> compileClasspath) throws IOException {
        return new RemoteCompiler(javaExecutable, jvmArgs, compileClasspath);
    }

    /**
     * Create a wrapper around the Java compiler on the current jdk.
     *
     * @param compileClasspath The compile classpath to use.
     * @return The Compiler.
     */
    static Compiler forLocal(Collection<Path> compileClasspath) throws IOException {
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
    default CompileResult compile(URI sourceUri, String source, List<String> extraJavacArgs) {
        return compile(Collections.singletonList(new CompileUnit(sourceUri, source)), extraJavacArgs);
    }

    /**
     * Request multiple compilation units be compiled in a single compiler task.
     *
     * @param units          The compilation units to compile.
     * @param extraJavacArgs Any additional Java arguments to provide.
     * @return The result.
     */
    CompileResult compile(Collection<CompileUnit> units, List<String> extraJavacArgs);

    /**
     * Release any resources and stop any sub-processes.
     */
    @Override
    void close() throws IOException;

    final class CompileUnit implements Serializable {

        /**
         * The URI describing the location of the source file.
         * Javac expects that packages are present in this URI.
         */
        public final URI sourceUri;
        /**
         * The string content of the file.
         */
        public final String source;

        public CompileUnit(URI sourceUri, String source) {
            this.sourceUri = sourceUri;
            this.source = source;
        }
    }

    /**
     * The result of a compile operation.
     */
    final class CompileResult implements Serializable {

        /**
         * The compiler output.
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
