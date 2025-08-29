package net.javasauce.compilerserver;

import org.jetbrains.annotations.Nullable;

import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import java.io.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Implements shared classpath caching for Javac.
 * <p>
 * Our test framework runs a separate Javac instance for each class we recompile. Unfortunately
 * Javac8 only has some vague caching for bootstrap regular class paths. It ends up re-reading
 * the bootstrap and regular class path jars constantly, degrading performance. This issue is
 * exponentially worse on J11/J17 as they use NIO ZipFS, which completely tanks performance.
 * <p>
 * This class implements a shared cache for both the Java8 bootstrap classpath and the regular classpath,
 * indexing the jars/dirs once on creation.
 * <p>
 * This implementation of shared classpath lacks the ability for javac to inherit the running JVM's classpath.
 */
// TODO support jmods? Tried to do this before, quite complicated and weird.
class FastJavacClasspathIndex implements Closeable {

    private final List<ZipFile> openZips = new ArrayList<>();
    private final Map<JavaFileManager.Location, Map<String, List<JavaFileObject>>> index = new HashMap<>();

    public void addPath(JavaFileManager.Location location, Path path) throws IOException {
        String fName = path.getFileName().toString();

        if (fName.endsWith(".jar") || fName.endsWith(".zip")) {
            indexZip(location, path.toFile());
        } else if (fName.endsWith(".jmod")) {
            // Ignore jmods, we let Javac handle these for now.
        } else if (Files.isDirectory(path)) {
            indexDirectory(location, path);
        } else {
            throw new IllegalArgumentException("Unknown file type, can't index. " + path);
        }
    }

    private void indexZip(JavaFileManager.Location location, File file) throws IOException {
        ZipFile zip = new ZipFile(file);
        openZips.add(zip);
        for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
            ZipEntry entry = e.nextElement();
            if (entry.isDirectory()) continue;

            String fPath = entry.getName();
            if (fPath.startsWith("/")) {
                fPath = fPath.substring(1);
            }
            addEntry(location, new ZipFileObject(file, zip, entry, fPath));
        }
    }

    private void indexDirectory(JavaFileManager.Location location, Path dir) throws IOException {
        try (Stream<Path> dirStream = Files.walk(dir)) {
            for (Path path : ((Iterable<Path>) dirStream::iterator)) {
                if (Files.isDirectory(path)) continue;

                String fPath = dir.relativize(path).toString().replace('\\', '/');
                addEntry(location, new PathObject(path, fPath));
            }
        }
    }

    private void addEntry(JavaFileManager.Location location, JavaFileObject obj) {
        String name = obj.getName();
        int lastSlash = name.lastIndexOf("/");
        String baseName = (lastSlash == -1 ? "" : name.substring(0, lastSlash)) + "/";

        index.computeIfAbsent(location, e -> new HashMap<>())
                .computeIfAbsent(baseName, e -> new ArrayList<>())
                .add(obj);
    }

    public JavaFileManager fileManager(JavaFileManager delegate) {
        return new ForwardingJavaFileManager<JavaFileManager>(delegate) {

            @Override
            public String inferBinaryName(Location location, JavaFileObject file) {
                if (file instanceof AbstractJavaFileObject) {
                    // Convert file name to 'binary name' `java/lang/Object.class` -> `java.lang.Object`
                    String fName = file.getName();
                    int lastDot = fName.lastIndexOf('.');
                    if (lastDot != -1) {
                        fName = fName.substring(0, lastDot);
                    }
                    return fName.replace('/', '.');
                }
                return super.inferBinaryName(location, file);
            }

            @Override
            public Iterable<JavaFileObject> list(Location location, String packageName, Set<JavaFileObject.Kind> kinds, boolean recurse) throws IOException {
                Map<String, List<JavaFileObject>> locationIndex = index.get(location);
                if (locationIndex == null) {
                    return super.list(location, packageName, kinds, recurse);
                }

                String folder = packageName.replace('.', '/') + "/";
                // TODO perhaps we should replace this with FastStream again.
                Stream<JavaFileObject> toFilter;
                if (!recurse) {
                    List<JavaFileObject> entries = locationIndex.get(folder);
                    if (entries == null) return Collections.emptyList();

                    toFilter = entries.stream();
                } else {
                    toFilter = locationIndex.entrySet().stream()
                            .filter(e -> e.getKey().startsWith(folder))
                            .flatMap(e -> e.getValue().stream());
                }

                if (!kinds.isEmpty()) {
                    toFilter = toFilter.filter(e -> kinds.contains(e.getKind()));
                }
                return toFilter.collect(Collectors.toList());

            }
        };
    }

    @Override
    public void close() throws IOException {
        for (ZipFile zip : openZips) {
            zip.close();
        }
    }

    public static class PathObject extends AbstractJavaFileObject {

        private final Path file;

        public PathObject(Path file, String fPath) {
            super(fPath);
            this.file = file;
        }

        @Override
        public URI toUri() {
            return file.toUri();
        }

        @Override
        public InputStream openInputStream() throws IOException {
            return Files.newInputStream(file);
        }
    }

    public static class ZipFileObject extends AbstractJavaFileObject {

        private final File zipFile;
        private final ZipFile archive;
        private final ZipEntry entry;

        public ZipFileObject(File zipFile, ZipFile archive, ZipEntry entry, String fPath) {
            super(fPath);
            this.zipFile = zipFile;
            this.archive = archive;
            this.entry = entry;
        }

        @Override
        public URI toUri() {
            // Kinda wierd, matches how Javac builds urls for zip entries.
            return URI.create("jar:"
                              + zipFile.toURI().normalize()
                              + (name.startsWith("/") ? "!" : "!/")
                              + name
            );
        }

        @Override
        public InputStream openInputStream() throws IOException {
            return archive.getInputStream(entry);
        }
    }

    private abstract static class AbstractJavaFileObject implements JavaFileObject {

        protected final String name;
        protected final Kind kind;

        public AbstractJavaFileObject(String name) {
            this.name = name;
            kind = findKind(name);
        }

        @Override
        public String getCharContent(boolean ignoreEncodingErrors) throws IOException {
            try (InputStream is = openInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                int len;
                while ((len = is.read(buf)) != -1) {
                    bos.write(buf, 0, len);
                }
                return bos.toString("UTF-8");
            }
        }

        @Override
        public Reader openReader(boolean ignoreEncodingErrors) throws IOException {
            return new StringReader(getCharContent(ignoreEncodingErrors));
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Kind getKind() {
            return kind;
        }

        @Override
        public boolean isNameCompatible(String simpleName, Kind kind) {
            return getKind().equals(kind) && getName().endsWith("/" + simpleName + kind.extension);
        }

        // @formatter:off
        @Override public OutputStream openOutputStream() { throw new UnsupportedOperationException(); }
        @Override public Writer openWriter() { throw new UnsupportedOperationException(); }
        @Override public long getLastModified() { return 0; }
        @Override public boolean delete() { return false; }
        @Override @Nullable public NestingKind getNestingKind() { return null; }
        @Override @Nullable public Modifier getAccessLevel() { return null; }
        // @formatter:on

        private static Kind findKind(String name) {
            if (name.endsWith(".class")) return Kind.CLASS;
            if (name.endsWith(".java")) return Kind.SOURCE;
            if (name.endsWith(".html")) return Kind.HTML;

            return Kind.OTHER;
        }
    }
}
