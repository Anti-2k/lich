package dev.lich.loader;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;

/** A Lich mod Lich found: its descriptor and where it came from (a jar, or a directory in development). */
public record LichMod(ModDescriptor descriptor, Path source) {
    public String id() {
        return descriptor.id();
    }

    public URL url() {
        try {
            return source.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalStateException("Bad mod path " + source, e);
        }
    }

    @Override
    public String toString() {
        return descriptor.id() + " " + descriptor.version() + " (" + source.getFileName() + ")";
    }
}
