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

    private final List<String> args;
    private final FastJavacClasspathIndex index;
    private final JavaCompiler compiler;

    public LocalCompiler(List<String> args, Collection<Path> compileClasspath, Collection<Path> compileSourcepath) throws IOException {
        this.args = args;
        index = new FastJavacClasspathIndex();
        for (Path path : compileClasspath) {
            index.addPath(StandardLocation.CLASS_PATH, path);
        }
        for (Path path : compileSourcepath) {
            index.addPath(StandardLocation.SOURCE_PATH, path);
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
    public CompileResult compile(Collection<CompileUnit> units, List<String> extraJavacArgs) {
        List<String> args = new ArrayList<>(this.args);
        // TODO should we demote -g and -proc:none to being supplied externally?
        args.add("-g");
        args.add("-proc:none");
        args.add("-XDuseUnsharedTable=true");
        args.addAll(extraJavacArgs);

        LocalJavaFileManager fileManager = new LocalJavaFileManager(compiler);
        StringWriter logWriter = new StringWriter();
        boolean result = false;
        Throwable javacCrash = null;
        try {
            JavaCompiler.CompilationTask task = compiler.getTask(
                    logWriter,
                    fileManager,
                    null,
                    args,
                    null,
                    units.stream()
                            .map(e -> new StringSource(e.sourceUri, e.source))
                            .collect(Collectors.toList())
            );

            result = task.call();
        } catch (Throwable ex) {
            javacCrash = ex;
        }
        return new CompileResult(
                fileManager.outputs,
                fileManager.references,
                result,
                logWriter.toString(),
                javacCrash
        );
    }

    @Override
    public void close() throws IOException {
        index.close();
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

    private class LocalJavaFileManager extends ForwardingJavaFileManager<JavaFileManager> {

        private final Map<String, byte[]> outputs = new LinkedHashMap<>();
        private final Set<String> references = new LinkedHashSet<>();

        public LocalJavaFileManager(JavaCompiler compiler) {
            super(index.fileManager(compiler.getStandardFileManager(null, null, null)));
        }

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

        @Override
        public Iterable<JavaFileObject> list(Location location, String packageName, Set<JavaFileObject.Kind> kinds, boolean recurse) throws IOException {
            Iterable<JavaFileObject> list = super.list(location, packageName, kinds, recurse);
            if (location != StandardLocation.SOURCE_PATH && location != StandardLocation.CLASS_PATH) return list;

            int expectedSize = list instanceof Collection ? ((Collection<?>) list).size() : 0;
            List<JavaFileObject> tracked = new ArrayList<>(expectedSize);
            list.forEach(e -> tracked.add(new TrackedFileObject(e, location)));
            return tracked;
        }

        @Override
        public String inferBinaryName(Location location, JavaFileObject file) {
            return super.inferBinaryName(location, unwrap(file));
        }

        @Override
        public boolean isSameFile(FileObject a, FileObject b) {
            return super.isSameFile(unwrap(a), unwrap(b));
        }

        @SuppressWarnings ("unchecked")
        private <T extends FileObject> T unwrap(T obj) {
            if (obj instanceof TrackedFileObject) {
                return (T) ((TrackedFileObject) obj).delegate();
            }
            return obj;
        }

        private class TrackedFileObject extends ForwardingJavaFileObject<JavaFileObject> {

            private final Location location;

            public TrackedFileObject(JavaFileObject fileObject, Location location) {
                super(fileObject);
                this.location = location;
            }

            public JavaFileObject delegate() {
                return fileObject;
            }

            private void onUsed() {
                references.add(inferBinaryName(location, fileObject).replace('.', '/'));
            }

            @Override
            public InputStream openInputStream() throws IOException {
                onUsed();
                return super.openInputStream();
            }

            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
                onUsed();
                return super.getCharContent(ignoreEncodingErrors);
            }

            @Override
            public Reader openReader(boolean ignoreEncodingErrors) throws IOException {
                onUsed();
                return super.openReader(ignoreEncodingErrors);
            }
        }
    }
}
