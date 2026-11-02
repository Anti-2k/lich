# Access wideners

An **access widener** (AW) opens up game members your code needs and the game keeps `private` or `final`: a field you
want to read, a method you want to call, a class you want to extend. Lich's format is Fabric's, so authors who know it
already know this. The implementation is `AccessWidener` in `mcdev/remap-core`, applied at run time by
`AccessWidenerTransformer` in `core/loader`.

Use an AW when *your own ordinary code* needs the access. A Mixin does not need one to reach private members
(`@Shadow`, `@Invoker`, `@Accessor` do that), and an AW cannot change behavior.

## Using one

1. Write the file in your resources: `src/main/resources/hello.aw`.
2. Tell the build, so your code compiles against the widened game: `accessWidener("hello.aw")` in the `lich { }` block.
3. Tell Lich, so the game is widened at run time: `"accessWidener": "hello.aw"` in `lich.mod.json`.

```kotlin
lich {
    minecraft("1.8.9")
    accessWidener("hello.aw")
}
```

```json
"accessWidener": "hello.aw"
```

You write the file with **MCP names**. When `reobfJar` builds the jar, it rewrites the widener to SRG names and marks
the file `srg`; that is the file Lich reads. A jar whose widener still says `named` is refused at launch:
"its access widener uses "named" names; a built mod's must use "srg" (the dev.lich.mod plugin rewrites it)". If you
see that, the jar was not made by `reobfJar`.

A mod has **one** widener file. The wideners of all loaded mods are merged into one and applied together.

## File format

```
accessWidener v2 named
# Comments start with #; blank lines are ignored.
accessible class  net/minecraft/client/renderer/GlStateManager$Color
accessible field  net/minecraft/client/Minecraft timer Lnet/minecraft/util/Timer;
mutable    field  net/minecraft/client/Minecraft debugFPS I
accessible method net/minecraft/client/Minecraft getSystemTime ()J
extendable class  net/minecraft/client/gui/GuiScreen
extendable method net/minecraft/client/gui/GuiScreen drawWorldBackground (I)V
```

- The header is `accessWidener v2 named` (`v1` is accepted too). The third word is the namespace; always write `named`.
- A class line: `<access> class <internal class name>`.
- A member line: `<access> field|method <owner internal name> <name> <descriptor>`.
- Names are **internal names** (slashes, `$` for inner classes); descriptors are JVM descriptors. The descriptor of a
  method includes the return type: `(II)V`.
- The `transitive-` prefix (`transitive-accessible`) is accepted and means the same as without it.

### What each access does

| Line | Effect |
|---|---|
| `accessible class` | The class becomes `public` |
| `accessible field` | The field becomes `public` (still `final` if it was) |
| `accessible method` | The method becomes `public`. If it was a private instance method it also becomes `final`, and the class's own `invokespecial` calls to it become `invokevirtual`, as Fabric does, so a subclass cannot override it by accident |
| `mutable field` | The field is no longer `final` |
| `extendable class` | The class becomes non-`final` (and `public`) |
| `extendable method` | A non-public method becomes `protected`; the method becomes non-`final` |
| `mutable method`, `extendable field` | Accepted, but do nothing |

A widened member makes its **class** public too, since a public member of a class nobody can see is of no use.

To write to a `private final` field you need both lines:

```
accessible field net/minecraft/client/Minecraft someField I
mutable    field net/minecraft/client/Minecraft someField I
```

As in plain Java, a `static final` constant may have been inlined by the compiler into the code that reads it, so
changing it at run time does not reach code compiled earlier.

## How it is applied

At run time `AccessWidenerTransformer` is one of Lich's **last** transformers, after Forge/FML's and after Mixin, by
SRG class name. Consequences:

- It applies to the game's classes as they are *after* Forge's binpatches. A Forge-patched member can be widened as
  long as it exists in the profile you run; test both profiles.
- It changes the class for **everyone** in the game, Forge mods included. A widened member is just more visible; it
  does not break anyone, with one exception: a private method widened with `accessible` becomes `final`, so a subclass
  (from another mod) that already declares a method with the same signature would fail to load, as it would then
  override a final method.
- Mixins see the unwidened class (see [Mixins](mixins.md)).
- With no mod that has an AW, the transformer is not installed at all.

In the **build**, the plugin applies the AW to the Minecraft jar your code compiles against (task `widenMinecraft`), so
the compiler and your IDE see the members as accessible. Change the AW, and Gradle widens again.

## Multi-version jars

An AW has one version's names, so it lives in that version's source set and is listed in the version's part of the
descriptor:

```
src/mc1_8_9/resources/hello.1_8_9.aw
src/mc1_7_10/resources/hello.1_7_10.aw
```

```kotlin
lich {
    minecraft("1.7.10", "1.8.9")
    forMinecraft("1.7.10") { accessWidener("hello.1_7_10.aw") }
    forMinecraft("1.8.9")  { accessWidener("hello.1_8_9.aw") }
}
```

```json
"versions": {
  "1.7.10": { "accessWidener": "hello.1_7_10.aw" },
  "1.8.9":  { "accessWidener": "hello.1_8_9.aw" }
}
```

A version's `accessWidener` **replaces** the top-level one (unlike lists, which add). Details in
[Several Minecraft versions in one jar](multi-version.md).

## Errors

| Message | Cause |
|---|---|
| `Not an access widener (expected "accessWidener v2 <namespace>")` | Missing or malformed header |
| `Unknown access widener access: ...` / `Unknown access widener target: ...` | A line starts with something other than `accessible`, `mutable`, `extendable` (and `transitive-` forms), or targets something other than `class`, `field`, `method` |
| `Expected "<access> field <owner> <name> <descriptor>"` | Wrong number of words on a member line |
| `Access widener hello.aw is not in [...]` (Gradle) | The path given to `accessWidener(...)` is not in the source set's resources |
| `its access widener hello.aw is missing` | The path in `lich.mod.json` is not in the jar |
| `uses "named" names` | See above |
