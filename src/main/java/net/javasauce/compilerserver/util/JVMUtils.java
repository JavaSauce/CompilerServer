package net.javasauce.compilerserver.util;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Created by covers1624 on 12/4/21.
 */
public class JVMUtils {

    private static final String JAVA_BOOT_CLASS_PATH = "sun.boot.class.path";

    public static List<Path> getRuntimeJREPaths() {
        // J8
        List<Path> paths = listSysProp(JAVA_BOOT_CLASS_PATH);
        if (paths == null) {
            // J9+
            paths = listJMods();
        }
        return paths;
    }

    private static @Nullable List<Path> listSysProp(String sysProp) {
        String value = System.getProperty(sysProp);
        if (value == null) return null;

        return Stream.of(value.split(File.pathSeparator))
                .distinct()
                .map(Paths::get)
                .filter(Files::exists)
                .collect(Collectors.toList());
    }

    private static List<Path> listJMods() {
        String javaHomeProp = System.getProperty("java.home");
        if (javaHomeProp == null) throw new IllegalStateException("Incompatible runtime: 'java.home' system property is missing.");

        Path javaHome = Paths.get(javaHomeProp);
        if (Files.notExists(javaHome)) throw new IllegalStateException("Incompatible runtime: 'java.home' system property resolves to non-existent path.");

        Path jModFolder = javaHome.resolve("jmods");
        if (Files.notExists(jModFolder)) throw new IllegalStateException("Incompatible runtime: '${java.home}/jmods' does not exist.");

        try (Stream<Path> files = Files.list(jModFolder)) {
            return files.collect(Collectors.toList());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list jmods directory.");
        }
    }
}
