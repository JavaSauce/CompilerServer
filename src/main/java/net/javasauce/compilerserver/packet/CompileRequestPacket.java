package net.javasauce.compilerserver.packet;

import java.io.Serializable;
import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Created by covers1624 on 8/29/25.
 */
public class CompileRequestPacket implements Serializable {

    public final UUID id;
    public final URI sourceUri;
    public final String source;
    public final List<String> compilerArgs;

    public CompileRequestPacket(UUID id, URI sourceUri, String source, List<String> compilerArgs) {
        this.id = id;
        this.sourceUri = sourceUri;
        this.source = source;
        this.compilerArgs = compilerArgs;
    }
}
