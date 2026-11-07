# Several Minecraft versions in one jar

One jar can run on **1.7.10, 1.8.9 and 1.12.2**. It is built once with the `dev.lich.mod` plugin's multi-version mode, and
Lich picks the right parts at launch. The example is `test/multimod`; read it next to this page.

## When to do it

Most mods target one version, and that is simpler: `minecraft("1.8.9")`, done. Build for several when your code really
runs on each:

- code that only uses `dev.lich.api` (the version-neutral API, with no game types), or
- code that uses game members whose **SRG names are the same** on both versions.

Many names did change between 1.7.10 and 1.8 (1.8 introduced `BlockPos`, block states and the new renderer), and again by 1.12 (`ITextComponent`, `RayTraceResult`, `EnumHand`, `Minecraft.player`). The
same MCP name does not even guarantee the same SRG name: `ModelBiped.bipedHeadwear` is `field_78114_d` on 1.7.10 and
`field_178720_f` on 1.8.9. The build checks all of this for you (see [the check](#the-check)); you do not have to know.

Everything that differs goes in a **per-version part**: its own Mixins, access widener, entrypoints.

## Build setup

```kotlin
plugins {
    id("lich.java")
    id("dev.lich.mod")
}

lich {
    minecraft("1.7.10", "1.8.9")
    forMinecraft("1.7.10") {
        mixinRefmap("mixins.hello.1_7_10.refmap.json")
        accessWidener("hello.1_7_10.aw")
    }
    forMinecraft("1.8.9") {
        mixinRefmap("mixins.hello.1_8_9.refmap.json")
        accessWidener("hello.1_8_9.aw")
    }
}

dependencies {
    compileOnly(project(":api"))                       // the version-neutral API, for src/main
    "mc1_7_10CompileOnly"(project(":v1_7_10-api"))     // events with game types, for 1.7.10's code
    "mc1_8_9CompileOnly"(project(":v1_8_9-api"))
}
```

`mixinRefmap` and `accessWidener` are no longer set directly in `lich { }` (the plugin refuses, saying to use
`forMinecraft`); they are per version.

### Source layout

```
src/main/java/dev/example/hello/Hello.java           shared code, compiled against every listed version
src/main/resources/lich.mod.json                      one descriptor
src/mc1_7_10/java/dev/example/hello/v1_7_10/...       1.7.10-only code, Mixins
src/mc1_7_10/resources/mixins.hello.1_7_10.json
src/mc1_7_10/resources/hello.1_7_10.aw
src/mc1_8_9/java/dev/example/hello/v1_8_9/...
src/mc1_8_9/resources/mixins.hello.1_8_9.json
src/mc1_8_9/resources/hello.1_8_9.aw
```

The source set for version `1.7.10` is `mc1_7_10` (dots become underscores). Its compile configurations are named
after it: `mc1_7_10CompileOnly`, `mc1_7_10Implementation`. Each version's code is compiled against **that version's**
Minecraft, the shared code, and what it depends on.

Rules:

- **Per-version classes need a package of their own** (`...v1_7_10`, `...v1_8_9`). Two versions may not contain a
  class or a resource with the same path but different bytes; that is an error (below).
- **Shared code cannot hold Mixins.** Refmaps are per version, and the shared classes are not compiled with the
  Mixin processor. Mixins go in a version's source set. `reobfJar` fails if it finds a Mixin in `src/main`.
- Resource names must be unique per version too: `hello.1_7_10.aw`, not `hello.aw` in both.
- `src/main/resources` holds the descriptor. It is shared and so identical for every version, which is why the
  per-version differences are in its `versions` block rather than in separate descriptors.

### What the build produces

| Task | Output |
|---|---|
| `compileJava` | `src/main` against the **first** version listed (the one your IDE sees) |
| `compileJavaFor1_8_9` (one per further version) | `src/main` again, against that version |
| `devJar1_7_10`, `devJar1_8_9` | the mod as it is on each version, MCP names |
| `reobfJar1_7_10`, `reobfJar1_8_9` | each, renamed to that version's SRG names |
| `reobfJar` | the merged jar: `build/libs/<name>-<version>.jar`. This is the jar to ship |

Per-version jars are in `build/lich/`.

## The descriptor

```json
{
  "schema": 1,
  "id": "hello",
  "version": "1.0.0",
  "minecraft": ["1.7.10", "1.8.9"],
  "depends": { "lich": ">=0.3.1", "forge": "optional" },
  "entrypoints": {
    "client": ["dev.example.hello.Hello"]
  },
  "javaVersion": 25,
  "versions": {
    "1.7.10": {
      "entrypoints": { "client": ["dev.example.hello.v1_7_10.Client"] },
      "mixins": ["mixins.hello.1_7_10.json"],
      "accessWidener": "hello.1_7_10.aw"
    },
    "1.8.9": {
      "entrypoints": { "client": ["dev.example.hello.v1_8_9.Client"] },
      "mixins": ["mixins.hello.1_8_9.json"],
      "accessWidener": "hello.1_8_9.aw"
    }
  }
}
```

- `minecraft` lists the versions the jar runs on (a whitelist; see the [reference](lich-mod-json.md#minecraft)).
- Each key of `versions` is an **exact** Minecraft version the whitelist accepts. A part may set only
  `entrypoints`, `hooks`, `provides`, `mixins` and `accessWidener`. Any other key, or a version the whitelist does not
  accept, is an error, so a typo cannot silently drop a Mixin.
- Lists (`entrypoints` per kind, `hooks`, `provides`, `mixins`) are **added** to the top-level ones. `accessWidener`
  **replaces** the top-level one.
- The loader merges the running version's part in before it resolves mods, so `provides` conflicts and everything
  after see the mod as it is on this version.
- **The plugin does not write the `versions` block.** You list the same Mixin configs, widener and entrypoints
  there that you put in the source sets, and they have to match: test the jar on each version once.

## The check

`reobfJar` merges the per-version jars. A file that is in more than one of them must be **byte for byte the same**.
For a class in `src/main` that is the proof that it links to the same SRG names on every version. If it does not, the
build names the file and the versions:

```
These files differ between the Minecraft versions they're built for:
  - dev/example/hello/Hello.class (1.7.10, 1.8.9)
A class in src/main must compile to the same SRG code on every version, so it can't use what differs between them:
move that into each version's src/mc<version>/java, in a package of its own. ...
```

Fixing it: find the call in that class that has a different SRG name or does not exist on one version, and move the
code that makes it into the per-version source sets, behind an interface or a static helper both versions implement.
The shared class then only calls your own code.

The game's own libraries differ between versions too, and javac picks overloads from them. The common trap is
logging: 1.12.2 ships log4j 2.8.1, whose `info(String, Object)` and `info(String, Object, Object)` overloads do not
exist in the 2.0-beta9 that 1.7.10 and 1.8.9 ship, so `LOG.info("x {}", value)` in a shared class compiles to
different methods and fails the check. Build one string (`LOG.info("x " + value)`) in shared code.

A common shape (this is what `multimod` does):

```java
// src/main: shared
public final class Hello implements ClientEntrypoint {
    @Override public void onClientReady() { /* version-neutral work */ }
}
```

```java
// src/mc1_8_9: version-specific, listed under versions."1.8.9".entrypoints
public final class Client implements ClientEntrypoint {
    @Override public void onClientReady() { /* 1.8.9 game code */ }
}
```

Both are listed as `client` entrypoints for 1.8.9 (the top-level one plus the version's), and only the shared one on a
version with no part.

## Runtime behaviour

- The one jar goes in `mods/` like any other. Lich picks the running version's part, merges it, and ignores the other
  version's files in the jar.
- A jar whose `minecraft` does not list the running version is refused with an error naming the versions it supports.
- If you call `Lich.minecraftVersion()` in shared code, you can branch on it, but only with code that links on both
  versions. Anything that wouldn't link belongs in a per-version class.

## Try it

`:multimod:installDev` copies the example jar into devlaunch's four profiles. Without touching a mods folder:

```
./gradlew :devlaunch:runGame "-Plich.profile=lich" "-Plich.mc=1.7.10" "-Plich.gameJavaHome=<JDK 25 home>" "-Plich.args=--jvm-arg -Dlich.devMods=<path to the jar>"
```

then the same for 1.8.9, with and without Forge (`lich-forge`). The example logs one `Lich multimod:` line per part.
