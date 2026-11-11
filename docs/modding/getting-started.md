# Getting started

By the end of this page you will have a Lich mod that draws a line of text on the HUD and counts ticks, built into a
jar and running in the game.

## What you need

- **JDK 25** (Temurin 25 is what Lich is tested with). It builds your mod and runs Gradle. Gradle in this project
  does not download JDKs; install it yourself.
- **A checkout of the Lich repository**, or from Lich 0.5.0 its Maven repository, see below.
- Internet on the first build. The `dev.lich.mod` plugin downloads the Minecraft client, its libraries and MCP's
  names once and keeps them in `<Gradle user home>/caches/lich-mcdev`, shared by every build on the machine.
- To play-test: either `tools/devlaunch` from the same checkout, or a [Prism](https://prismlauncher.org/) instance
  with Lich installed (see the [README](https://github.com/Anti-2k/lich/blob/main/README.md#installing)).

### Where your mod lives

This page builds the mod as **a module inside a checkout of the Lich repository**, with the repository's Gradle
build. The mods in `test/` and `mods/` are laid out exactly that way. Your finished jar is a plain file you can hand to
anyone; only building it needs the checkout.

From Lich 0.5.0, a mod can also live in a repository of its own: each release publishes the API and the `dev.lich.mod`
plugin to Lich's Maven repository, `https://anti-2k.github.io/lich/maven`. Add it to `pluginManagement` and to the
dependency repositories (next to Maven Central and `https://maven.fabricmc.net` for Mixin), apply
`id("dev.lich.mod") version "0.5.0"`, and depend on `compileOnly("dev.lich:v1_8_9-api:0.5.0")` instead of the project.
The `lich.java` convention used below belongs to the Lich build: set the Java 25 toolchain and `options.release = 25`
yourself. A project template is on the roadmap
([DOCS.md §8](https://github.com/Anti-2k/lich/blob/main/DOCS.md#8-roadmap)).

## 1. Create the module

Pick a directory, for example `mods/hello`, and register it in the root `settings.gradle.kts` next to the other
first-party mods:

```kotlin
module("hello", "mods/hello")
```

(`module(name, dir)` is the helper at the top of that file. The project name `hello` becomes part of the jar's
name, `lich-hello-<version>.jar`.)

Create `mods/hello/build.gradle.kts`:

```kotlin
plugins {
    id("lich.java")      // Java 25, UTF-8, lint, JUnit 5; jars are named lich-<project>
    id("dev.lich.mod")   // Minecraft with MCP names to compile against; refmaps; reobfuscation to SRG
}

version = "1.0.0"        // the jar's version; without it the repository's (gradle.properties) is used

lich {
    minecraft("1.8.9")
    // Only if the mod has Mixins / an access widener (see their pages):
    // mixinRefmap("mixins.hello.refmap.json")
    // accessWidener("hello.aw")
}

dependencies {
    // Lich's API for 1.8.9: the version-neutral part (:api) comes with it.
    compileOnly(project(":v1_8_9-api"))
}
```

`compileOnly`, because at run time the API is provided by Lich itself (it is installed next to the game, in the same
class loader as the game and your mod). Never bundle it.

For 1.7.10 use `minecraft("1.7.10")` and `:v1_7_10-api`; for 1.12.2 `minecraft("1.12.2")` and `:v1_12_2-api`. For both versions in one jar, see
[Several Minecraft versions in one jar](multi-version.md).

What `minecraft("1.8.9")` gives you:

- vanilla 1.8.9 and its libraries on the compile classpath, renamed to MCP names. **No Forge.** Whatever compiles
  here only uses what vanilla has, so it runs with and without Forge;
- the libraries are the game's own (log4j 2.0-beta9, Gson 2.2.4, Guava, commons-lang3, ...). You compile against the
  versions the game ships, so you cannot accidentally use an API that is not there at run time;
- Mixin 0.8.7 and MixinExtras on the compile classpath (`compileOnly`; Lich provides them at run time);
- a `jar` task that produces the **dev jar** (`lich-hello-<version>-dev.jar`, MCP names) and a `reobfJar` task that
  produces the jar the game loads (`lich-hello-<version>.jar`, SRG names). `assemble` builds the latter.

The version in the jar's file name is the project's Gradle `version`, which defaults to the repository's
(`gradle.properties`); that is why the build file above sets its own. Keep it in step with `"version"` in
`lich.mod.json`: Gradle does not.

## 2. Write the descriptor

`mods/hello/src/main/resources/lich.mod.json`:

```json
{
  "schema": 1,
  "id": "hello",
  "version": "1.0.0",
  "name": "Hello Lich",
  "description": "Draws a line of text on the HUD.",
  "authors": ["You"],
  "minecraft": "1.8.9",
  "depends": {
    "lich": ">=0.3",
    "forge": "optional"
  },
  "entrypoints": {
    "init": ["dev.example.hello.Hello"]
  },
  "hooks": ["client.tick", "render.hud"],
  "javaVersion": 25
}
```

Everything is explained in the [`lich.mod.json` reference](lich-mod-json.md). The two lines to understand now:
`entrypoints` names the class Lich instantiates, and `hooks` lists the hook ids the mod listens to. **If you register
for an event whose hook is not listed, Lich refuses with an error that names your mod and the hook.** A hook you list
but never use is still woven, so list only what you use.

## 3. Write the code

`mods/hello/src/main/java/dev/example/hello/Hello.java`:

```java
package dev.example.hello;

import dev.lich.api.client.ClientTickEvents;
import dev.lich.api.client.HudElement;
import dev.lich.api.client.HudEvents;
import dev.lich.api.entrypoint.InitEntrypoint;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class Hello implements InitEntrypoint {
    private static final Logger LOG = LogManager.getLogger("hello");

    private int ticks;

    @Override
    public void onInit() {
        ClientTickEvents.END.register(tick -> ticks++);

        HudEvents.POST.register(render -> {
            if (render.element() == HudElement.ALL) {
                Minecraft.getMinecraft().fontRendererObj
                        .drawStringWithShadow("Hello from Lich, ticks: " + ticks, 2, 2, 0xFF55FF55);
            }
        });
        LOG.info("hello: registered");
    }
}
```

Points worth noticing:

- The entrypoint class must be **public with a public no-argument constructor**. Lich creates one instance per class,
  so a class that is listed under several entrypoint kinds keeps its fields between them.
- You write `Minecraft`, `fontRendererObj` and the other game names the way MCP names them. The jar is renamed to SRG
  for you in `reobfJar`.
- `HudElement.ALL` is "the whole HUD". `POST` for it fires after everything else of the HUD was drawn.
- Logging goes through log4j 2 like the game's own. Use `LogManager.getLogger(...)`; do not print to `System.out`.

## 4. Build

```
./gradlew :hello:reobfJar
```

The finished jar is `mods/hello/build/libs/lich-hello-1.0.0.jar`. (`./gradlew :hello:build` also runs your tests
and writes the `-dev` jar next to it.)

In IntelliJ or Eclipse, import the Lich repository as a Gradle project. The game is on your classpath as class files
with MCP names; no game sources or Javadoc are attached, so your IDE decompiles or shows signatures only.

## 5. Run it

### With devlaunch

`devlaunch` is the repository's own launcher. `-Dlich.devMods` takes jars (or directories) separated by the path
separator and treats them as installed mods, so you do not have to copy anything:

```
./gradlew :devlaunch:runGame "-Plich.profile=lich" "-Plich.mc=1.8.9" "-Plich.gameJavaHome=<JDK 25 home>" "-Plich.args=--jvm-arg -Dlich.devMods=C:/path/to/lich/mods/hello/build/libs/lich-hello-1.0.0.jar"
```

In PowerShell, quote every `-P` argument (as above), or it splits them at the dot. Profiles:

| Profile | Meaning |
|---|---|
| `lich` | Lich, no Forge |
| `lich-forge` | Lich with Forge (also `-Plich.mc=1.7.10` and `-Plich.mc=1.12.2`; for 1.12.2 `-Plich.forge=14.23.5.2860` or `14.23.5.2864` picks that build instead of the recommended one) |

Try your mod on both: it must work with and without Forge. For the full list of devlaunch options see
[AGENTS.md](https://github.com/Anti-2k/lich/blob/main/AGENTS.md#running-the-game-devlaunch). Add `--jvm-arg -Dlich.dev.noMouseGrab=true` to the
arguments if you want to keep using your desktop while the game runs: the game then behaves as if it grabbed the
mouse but never captures your real cursor.

Each profile has its own game directory, `run/<profile>-<mc>/`. A jar copied into its `mods/` folder is picked up
the same way as `-Dlich.devMods`. The example mods have a `:testmod:installDev` task that does exactly that;
you can copy it from `test/testmod/build.gradle.kts`.

### In Prism

Copy the jar into the instance's `minecraft/mods` folder, like any mod. The instance must have Lich installed, with
Forge or without. Use an instance you made for testing, not the one you play on.

### Did it load?

Read the game log (`run/<profile>-<mc>/logs/latest.log`, or the instance's `minecraft/logs/latest.log`). Boot logs one
line for the mods it found and the hooks they asked for:

```
Lich mods: [hello 1.0.0 (lich-hello-1.0.0.jar)]; hooks: [render.hud, client.tick, lifecycle]
```

The mods are in load order. The hooks are the union of every loaded mod's `hooks` list plus `lifecycle`, which every
Lich mod gets, in no particular order. Other Lich mods in the same `mods` folder add to both lists. Your entrypoint's
own log line (`hello: registered`) comes later, on the client thread. If the line is missing, no mod
was found: check the jar is in `mods/` and has `lich.mod.json` at its root. If a mod cannot load, the game stops
before its window opens and the log lists every problem at once, see [Troubleshooting](troubleshooting.md).

## Next steps

- Understand when your code runs: [Lifecycle and entrypoints](lifecycle.md).
- See what you can listen to: [Events and hooks](events-and-hooks.md).
- Need to change something the events do not cover: [Mixins](mixins.md), [Access wideners](access-wideners.md).
- Want the mod on 1.7.10 as well: [Several Minecraft versions in one jar](multi-version.md).
