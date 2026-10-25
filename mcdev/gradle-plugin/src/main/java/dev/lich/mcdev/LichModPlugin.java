package dev.lich.mcdev;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaLibraryPlugin;

/**
 * {@code dev.lich.mod}: builds a Lich mod (or a Lich module that touches the game) against vanilla
 * Minecraft with MCP names. Configure it with the {@code lich} extension ({@link LichExtension}); the jar
 * task then makes the dev jar (MCP names, classifier {@code dev}) and {@code reobfJar} the jar the game
 * loads (SRG names), which is also what other projects get when they depend on this one.
 */
public final class LichModPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(JavaLibraryPlugin.class);
        project.getExtensions().create("lich", LichExtension.class, project);
    }
}
