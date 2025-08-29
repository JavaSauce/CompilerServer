package net.javasauce.compilerserver;

import net.javasauce.compilerserver.packet.CompileRequestPacket;
import net.javasauce.compilerserver.packet.CompileResultPacket;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A safer {@link ObjectInputStream} which only allows deserializing specific classes.
 * <p>
 * Created by covers1624 on 8/29/25.
 */
class SaferObjectInputStream extends ObjectInputStream {

    public final Set<String> allowedClasses = new HashSet<>();
    public final List<String> allowedPackages = new ArrayList<>();

    public SaferObjectInputStream(InputStream is) throws IOException {
        super(is);
        allowedClasses.add("boolean");
        allowedClasses.add("byte");
        allowedClasses.add("char");
        allowedClasses.add("short");
        allowedClasses.add("int");
        allowedClasses.add("long");
        allowedClasses.add("float");
        allowedClasses.add("double");
        allowedClasses.add("void");

        allowedPackages.add("java.util");
        allowedPackages.add("java.lang");

        addAllowedClass(URI.class);
        addAllowedClass(CompileRequestPacket.class);
        addAllowedClass(CompileResultPacket.class);
        addAllowedClass(Compiler.CompileResult.class);
    }

    public void addAllowedClass(Class<?> clazz) {
        allowedClasses.add(clazz.getName());
    }

    private boolean isTypeAllowed(String type) {
        if (allowedClasses.contains(type)) return true;

        if (type.charAt(0) == 'L' && type.charAt(type.length() - 1) == ';') return isTypeAllowed(type.substring(1, type.length() - 1));
        if (type.charAt(0) == '[') {
            if (type.length() == 2) return true; // Primitive arrays.

            return isTypeAllowed(type.substring(1));
        }

        for (String allowedPackage : allowedPackages) {
            if (type.startsWith(allowedPackage + ".")) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected Class<?> resolveClass(ObjectStreamClass desc) throws IOException, ClassNotFoundException {
        if (!isTypeAllowed(desc.getName())) throw new ClassNotFoundException("Class " + desc.getName() + " is not allowed to be deserialized.");

        return super.resolveClass(desc);
    }
}
