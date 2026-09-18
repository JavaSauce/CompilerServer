package net.javasauce.compilerserver.packet;

import java.io.File;
import java.io.Serializable;
import java.util.List;

/**
 * Created by covers1624 on 9/18/26.
 */
public class ConfigureCompilerPacket implements Serializable {

    public final List<String> args;
    public final List<File> classPath;
    public final List<File> sourcePath;

    public ConfigureCompilerPacket(List<String> args, List<File> classPath, List<File> sourcePath) {
        this.args = args;
        this.classPath = classPath;
        this.sourcePath = sourcePath;
    }
}
