package net.javasauce.compilerserver;

import net.javasauce.compilerserver.util.FastJavacClasspathIndex;
import net.javasauce.compilerserver.util.JVMUtils;
import net.javasauce.compilerserver.util.StringSource;
import org.jetbrains.annotations.Nullable;

import javax.tools.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;

/**
 * Created by covers1624 on 8/29/25.
 */
class LocalCompiler implements Compiler {

    private final FastJavacClasspathIndex index;
    private final JavaCompiler compiler;

    public LocalCompiler(List<Path> compileClasspath) throws IOException {
        index = new FastJavacClasspathIndex();
        for (Path path : compileClasspath) {
            index.addPath(StandardLocation.CLASS_PATH, path);
        }
        for (Path e : JVMUtils.getRuntimeJREPaths()) {
            index.addPath(StandardLocation.PLATFORM_CLASS_PATH, e);
        }
        compiler = ToolProvider.getSystemJavaCompiler();
    }

    @Override
    public CompileResult compile(URI sourceUri, String source, List<String> extraJavacArgs) {
        List<String> args = new ArrayList<>();
        args.add("-g");
        args.add("-proc:none");
        args.add("-XDuseUnsharedTable=true");
        args.addAll(extraJavacArgs);

        Map<String, byte[]> outputs = new HashMap<>();
        StringWriter logWriter = new StringWriter();
        JavaCompiler.CompilationTask task = compiler.getTask(
                logWriter,
                getFileManager(compiler, outputs),
                null,
                args,
                null,
                Collections.singleton(new StringSource(sourceUri, source))
        );
        boolean result = false;
        Throwable javacCrash = null;
        try {
            result = task.call();
        } catch (Throwable ex) {
            javacCrash = ex;
        }
        return new CompileResult(
                outputs,
                result,
                logWriter.toString(),
                javacCrash
        );
    }

    @Override
    public void close() throws IOException {
        index.close();
    }

    private JavaFileManager getFileManager(JavaCompiler compiler, Map<String, byte[]> outputs) {
        return new ForwardingJavaFileManager<JavaFileManager>(index.fileManager(compiler.getStandardFileManager(null, null, null))) {
            @Override
            public JavaFileObject getJavaFileForOutput(Location location, String cName, JavaFileObject.Kind kind, FileObject sibling) {
                return new SimpleJavaFileObject(URI.create("output:///" + cName + ".class"), kind) {
                    @Override
                    public OutputStream openOutputStream() {
                        return new ByteArrayOutputStream() {
                            @Override
                            public void close() {
                                outputs.put(cName.replace('.', '/') + ".class", toByteArray());
                            }
                        };
                    }
                };
            }

            @Override
            public boolean hasLocation(Location location) {
                // Mark annotation processor path as supported,
                // Causes JavacProcessingEnvironment to query ANNOTATION_PROCESSOR_PATH instead of CLASS_PATH
                if (location == StandardLocation.ANNOTATION_PROCESSOR_PATH) return true;

                return super.hasLocation(location);
            }

            @Override
            @Nullable
            public ClassLoader getClassLoader(Location location) {
                // Just return null for ANNOTATION_PROCESSOR_PATH, causes javac to just not try and load plugins.
                if (location == StandardLocation.ANNOTATION_PROCESSOR_PATH) return null;

                return super.getClassLoader(location);
            }
        };
    }
}
