package net.javasauce.compilerserver.packet;

import net.javasauce.compilerserver.Compiler;

import java.io.Serializable;
import java.util.UUID;

/**
 * Created by covers1624 on 8/29/25.
 */
public class CompileResultPacket implements Serializable {

    public final UUID id;
    public final Compiler.CompileResult result;

    public CompileResultPacket(UUID id, Compiler.CompileResult result) {
        this.id = id;
        this.result = result;
    }
}
