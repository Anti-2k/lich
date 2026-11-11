// The version-neutral half of Lich's public API (DOCS.md §5.2): the Lich facade, entrypoints, the event bus,
// hook ids, and the events that carry no game types. The per-version half (versions/<mc>/api) adds the
// events with game types and the hooks' Mixins. Mods compile against both; at runtime both are in
// LaunchClassLoader, next to the game and the mods.
plugins {
    id("lich.java")
}

dependencies {
    implementation(project(":loader"))
}
