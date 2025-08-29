package net.javasauce.compilerserver.util;

import javax.tools.SimpleJavaFileObject;
import java.net.URI;

/**
 * Created by covers1624 on 8/29/25.
 */
public class StringSource extends SimpleJavaFileObject {

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
