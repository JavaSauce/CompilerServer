package net.javasauce.compilerserver.packet;

import net.javasauce.compilerserver.Compiler;

import java.io.Serializable;
import java.util.List;
import java.util.UUID;

/**
 * Created by covers1624 on 8/29/25.
 */
public class CompileRequestPacket implements Serializable {

    public final UUID id;
    public final List<Compiler.CompileUnit> units;
    public final List<String> compilerArgs;

    public CompileRequestPacket(UUID id, List<Compiler.CompileUnit> units, List<String> compilerArgs) {
        this.id = id;
        this.units = units;
        this.compilerArgs = compilerArgs;
    }
}
