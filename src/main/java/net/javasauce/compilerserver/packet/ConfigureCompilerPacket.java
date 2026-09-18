package net.javasauce.compilerserver.packet;

import java.io.File;
import java.io.Serializable;
import java.util.List;

/**
 * Created by covers1624 on 9/18/26.
 */
public class ConfigureCompilerPacket implements Serializable {

    public final List<File> classPath;

    public ConfigureCompilerPacket(List<File> classPath) {
        this.classPath = classPath;
    }
}
