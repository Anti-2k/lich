package dev.lich.installer;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The player-facing installer: adds Lich to a Prism Launcher instance, or creates one, with the Forge build Lich
 * supports and Java 25. A window when started without arguments (double-clicking the jar), a command line otherwise.
 * Lich's jars and the list of libraries Prism downloads come from the installer jar itself ({@link Payload}).
 */
public final class PrismInstaller {
    static final int MIN_JAVA = 25;
    private static final String USAGE = """
            Lich %s installer for Prism Launcher

              java -jar lich-installer.jar                     opens the installer window
              java -jar lich-installer.jar [options]

            Options:
              --instance <folder|name>  add Lich to this instance (its folder, or its name in Prism)
              --mc <1.8.9|1.7.10>       without --instance: create a new instance for this version (default 1.8.9)
              --name <name>             the new instance's name (default "<mc> Forge (Lich)")
              --no-forge                the new instance gets no Forge (Lich runs vanilla too)
              --yes                     allow changing an instance's Forge build to the one Lich supports
              --java <path>             the Java 25+ the instance runs on (default: the Java running this)
              --prism <dir>             Prism's data folder (default: found automatically)
              --uninstall               remove Lich from --instance
              --list                    list the instances and whether Lich can go into them
              --force                   go on even though Prism is running
            """.formatted(version());

    private PrismInstaller() {
    }

    /** What the installer tells the person running it, and what it asks them. */
    interface Ui {
        void log(String line);

        boolean confirm(String question);
    }

    /** Thrown for a problem the person can fix; its message is shown as is. */
    static final class InstallerException extends IOException {
        private static final long serialVersionUID = 1L;

        InstallerException(String message) {
            super(message);
        }
    }

    /**
     * Installs Lich into {@code instance}, or into a new instance called {@code newName} when it's null. Returns
     * the instance folder.
     */
    static Path install(Path dataDir, Path instance, String newName, String mc, boolean forge, Path java, Ui ui)
            throws IOException {
        int javaVersion = PrismLauncher.javaVersion(java);
        if (javaVersion < MIN_JAVA) {
            throw new InstallerException("Lich needs Java " + MIN_JAVA + " or newer; " + java + " is Java " + javaVersion
                    + ". Get one from https://adoptium.net/");
        }

        PrismInstance target;
        if (instance == null) {
            Path dir = PrismLauncher.newInstanceDir(dataDir, newName);
            target = PrismInstance.create(dir, newName, mc, forge);
            ui.log("Created instance \"" + newName + "\" (Minecraft " + mc
                    + (forge ? ", Forge " + PrismInstance.FORGE_VERSIONS.get(mc) : "") + ") in " + dir);
        } else {
            target = PrismInstance.read(instance);
            String problem = target.problem();
            if (problem != null) {
                throw new InstallerException("Lich can't go into \"" + target.name() + "\": " + problem + ".");
            }
            mc = target.minecraft();
            String change = target.forgeChange(forge);
            if (change != null) {
                if (!ui.confirm("\"" + target.name() + "\" needs a change for Lich: " + change + ". Mods made for another"
                        + " Forge build usually work on it, but not always. Go ahead?")) {
                    throw new InstallerException("Nothing changed.");
                }
                backUp(target.dir);
                target.ensureForge(forge);
                target.writePack();
                ui.log(Character.toUpperCase(change.charAt(0)) + change.substring(1) + ".");
            } else {
                backUp(target.dir);
            }
        }

        Payload payload = Payload.read(payloadDir(mc), mc);
        if (payload == null) {
            throw new InstallerException("This installer has no Lich build for Minecraft " + mc + ".");
        }
        PrismComponent.install(target.dir, mc, payload.version(), payload.jvmFlags(), payload.libraries());
        target.useJava(java);
        ui.log("Installed Lich " + payload.version() + " into \"" + target.name() + "\", running on " + java + ".");
        ui.log("Prism downloads the remaining libraries the first time the instance starts.");
        return target.dir;
    }

    /** Removes Lich from {@code instance} and hands its Java choice back to Prism. Forge stays. */
    static void uninstall(Path instance, Ui ui) throws IOException {
        PrismInstance target = PrismInstance.read(instance);
        if (!PrismComponent.isInstalled(instance)) {
            ui.log("Lich isn't installed in \"" + target.name() + "\".");
            return;
        }
        PrismComponent.uninstall(instance);
        target.resetJava();
        ui.log("Removed Lich from \"" + target.name() + "\". Its Forge and mods are as they were; Prism picks its Java again.");
    }

    /** One line about an instance, for lists: its name, versions, whether Lich is in it, or why it can't be. */
    static String describe(Path dir) {
        try {
            PrismInstance instance = PrismInstance.read(dir);
            String forge = instance.version(PrismInstance.FORGE);
            String problem = instance.problem();
            return instance.name() + " (Minecraft " + (instance.minecraft() == null ? "?" : instance.minecraft())
                    + (forge != null ? ", Forge " + forge : "")
                    + (PrismComponent.isInstalled(dir) ? ", Lich installed" : "") + ")"
                    + (problem != null ? ": can't install, " + problem : "");
        } catch (IOException | RuntimeException e) {
            return dir.getFileName() + ": unreadable (" + e.getMessage() + ")";
        }
    }

    /** The installer's (and so Lich's) version, from the jar's manifest. */
    static String version() {
        String version = PrismInstaller.class.getPackage().getImplementationVersion();
        return version == null ? "(dev)" : version;
    }

    static String defaultName(String mc, boolean forge) {
        return mc + (forge ? " Forge" : "") + " (Lich)";
    }

    /** The folder the payload is in: inside the installer jar, or on disk when run from the build. */
    private static Path payloadDir(String mc) throws IOException {
        URL resource = PrismInstaller.class.getResource("/lich/" + mc + ".json");
        if (resource == null) {
            throw new InstallerException("This installer has no Lich build for Minecraft " + mc + ".");
        }
        try {
            URI uri = resource.toURI();
            try {
                return Path.of(uri).getParent();
            } catch (FileSystemNotFoundException e) {
                // Inside the jar: open it once; it stays open for the rest of the run.
                return FileSystems.newFileSystem(uri, Map.of()).provider().getPath(uri).getParent();
            }
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
    }

    private static void backUp(Path instance) throws IOException {
        // Only the first time: a later reinstall must not overwrite the state from before Lich.
        for (String file : List.of("mmc-pack.json", "instance.cfg")) {
            Path backup = instance.resolve(file + ".before-lich");
            if (!Files.exists(backup)) {
                Files.copy(instance.resolve(file), backup);
            }
        }
    }

    public static void main(String[] args) {
        if (args.length == 0 && !GraphicsEnvironment.isHeadless()) {
            InstallerWindow.open();
            return;
        }
        try {
            System.exit(run(args));
        } catch (InstallerException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        } catch (IOException | RuntimeException e) {
            System.err.println("Install failed: " + e);
            System.exit(2);
        }
    }

    private static int run(String[] args) throws IOException {
        String instanceArg = null;
        String name = null;
        String mc = "1.8.9";
        boolean forge = true;
        boolean yes = false;
        boolean force = false;
        boolean uninstall = false;
        boolean list = false;
        Path java = null;
        Path dataDir = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--instance" -> instanceArg = value(args, ++i);
                case "--name" -> name = value(args, ++i);
                case "--mc" -> mc = value(args, ++i);
                case "--no-forge" -> forge = false;
                case "--yes" -> yes = true;
                case "--force" -> force = true;
                case "--uninstall" -> uninstall = true;
                case "--list" -> list = true;
                case "--java" -> java = Path.of(value(args, ++i));
                case "--prism" -> dataDir = Path.of(value(args, ++i));
                case "--help", "-h" -> {
                    System.out.print(USAGE);
                    return 0;
                }
                default -> throw new InstallerException("Unknown option " + args[i] + "\n\n" + USAGE);
            }
        }

        if (dataDir == null) {
            List<Path> found = PrismLauncher.findDataDirs();
            if (found.isEmpty()) {
                throw new InstallerException("Prism Launcher's data folder wasn't found. Is Prism installed? Pass --prism <dir>"
                        + " (for a portable Prism, the folder with prismlauncher.cfg).");
            }
            dataDir = found.getFirst();
        } else if (!PrismLauncher.isDataDir(dataDir)) {
            throw new InstallerException("Not a Prism data folder (no prismlauncher.cfg): " + dataDir);
        }
        if (list) {
            System.out.println("Instances in " + PrismLauncher.instancesDir(dataDir) + ":");
            for (Path dir : PrismLauncher.instances(dataDir)) {
                System.out.println("  " + dir.getFileName() + ": " + describe(dir));
            }
            return 0;
        }
        if (!PrismInstance.FORGE_VERSIONS.containsKey(mc)) {
            throw new InstallerException("Lich supports Minecraft 1.7.10 and 1.8.9, not " + mc + ".");
        }
        if (PrismLauncher.isRunning() && !force) {
            throw new InstallerException("Prism Launcher is running. Close it first (it would undo the changes), or pass --force.");
        }

        Path instance = instanceArg == null ? null : findInstance(dataDir, instanceArg);
        boolean accept = yes;
        Ui ui = new Ui() {
            @Override
            public void log(String line) {
                System.out.println(line);
            }

            @Override
            public boolean confirm(String question) {
                System.out.println(question);
                if (!accept) {
                    System.out.println("Rerun with --yes to accept.");
                }
                return accept;
            }
        };
        if (uninstall) {
            if (instance == null) {
                throw new InstallerException("--uninstall needs --instance.");
            }
            uninstall(instance, ui);
            return 0;
        }
        install(dataDir, instance, name != null ? name : defaultName(mc, forge), mc, forge,
                java != null ? java : PrismLauncher.currentJava(), ui);
        if (instance == null) {
            ui.log("Start Prism; the new instance is in its list (if Prism was open, restart it).");
        }
        return 0;
    }

    /** An instance by folder path, folder name or display name. */
    private static Path findInstance(Path dataDir, String spec) throws IOException {
        Path asPath = Path.of(spec);
        if (PrismInstance.isInstance(asPath)) {
            return asPath;
        }
        List<Path> instances = PrismLauncher.instances(dataDir);
        for (Path dir : instances) {
            if (dir.getFileName().toString().equals(spec)) {
                return dir;
            }
        }
        for (Path dir : instances) {
            if (PrismInstance.read(dir).name().equals(spec)) {
                return dir;
            }
        }
        throw new InstallerException("No instance \"" + spec + "\" in " + PrismLauncher.instancesDir(dataDir) + " (see --list).");
    }

    private static String value(String[] args, int i) throws InstallerException {
        if (i >= args.length) {
            throw new InstallerException(args[i - 1] + " needs a value.\n\n" + USAGE);
        }
        return args[i];
    }
}
