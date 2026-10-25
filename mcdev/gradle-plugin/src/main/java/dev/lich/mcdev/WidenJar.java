package dev.lich.mcdev;

import dev.lich.remap.core.AccessWidener;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * The Minecraft jar a mod compiles against, with the mod's access widener applied, so the compiler
 * allows what the widener allows at runtime. Without a widener it's a plain copy.
 */
@CacheableTask
public abstract class WidenJar extends DefaultTask {
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getInput();

    /** Zero or one file: the widener, with MCP names. */
    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getAccessWidener();

    @OutputFile
    public abstract RegularFileProperty getOutput();

    @TaskAction
    public void widen() throws IOException {
        File input = getInput().get().getAsFile();
        File output = getOutput().get().getAsFile();
        output.getParentFile().mkdirs();
        if (getAccessWidener().isEmpty()) {
            Files.copy(input.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        AccessWidener widener = AccessWidener.parse(Files.readString(getAccessWidener().getSingleFile().toPath()));
        try (ZipFile zip = new ZipFile(input);
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(output.toPath()))) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                byte[] bytes;
                try (InputStream in = zip.getInputStream(entry)) {
                    bytes = in.readAllBytes();
                }
                String name = entry.getName();
                if (name.endsWith(".class") && widener.targets(name.substring(0, name.length() - ".class".length()))) {
                    ClassReader reader = new ClassReader(bytes);
                    ClassWriter writer = new ClassWriter(0);
                    reader.accept(widener.visitor(writer), 0);
                    bytes = writer.toByteArray();
                }
                ZipEntry copy = new ZipEntry(name);
                copy.setTime(entry.getTime());
                out.putNextEntry(copy);
                out.write(bytes);
                out.closeEntry();
            }
        }
    }
}
