package net.javasauce.compilerserver;

import org.jetbrains.annotations.Nullable;

import javax.tools.*;
import java.io.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Created by covers1624 on 8/29/25.
 */
class LocalCompiler implements Compiler {

    private final FastJavacClasspathIndex index;
    private final JavaCompiler compiler;

    public LocalCompiler(Collection<Path> compileClasspath) throws IOException {
        index = new FastJavacClasspathIndex();
        for (Path path : compileClasspath) {
            index.addPath(StandardLocation.CLASS_PATH, path);
        }
        // TODO, FastJavacClasspathIndex doesn't support jmods. So, only handle the bootstrap classpath here.
        String bootClasspath = System.getProperty("sun.boot.class.path");
        if (bootClasspath != null) {
            List<Path> paths = Stream.of(bootClasspath.split(File.pathSeparator))
                    .distinct()
                    .map(Paths::get)
                    .filter(Files::exists)
                    .collect(Collectors.toList());
            for (Path path : paths) {
                index.addPath(StandardLocation.PLATFORM_CLASS_PATH, path);
            }
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
        boolean result = false;
        Throwable javacCrash = null;
        try {
            JavaCompiler.CompilationTask task = compiler.getTask(
                    logWriter,
                    getFileManager(compiler, outputs),
                    null,
                    args,
                    null,
                    Collections.singleton(new StringSource(sourceUri, source))
            );

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

    private static class StringSource extends SimpleJavaFileObject {

        private final String content;

        public StringSource(URI uri, String content) {
            super(uri, Kind.SOURCE);
            this.content = content;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return content;
        }
    }
}
