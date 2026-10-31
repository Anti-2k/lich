package dev.lich.loader;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code lich.mod.json}'s Minecraft whitelist and per-version parts (DOCS.md §5.1). */
class ModDescriptorTest {
    private static String json(String minecraft, String extra) {
        return "{\"schema\": 1, \"id\": \"example\", \"version\": \"1.0.0\", \"minecraft\": " + minecraft
                + (extra.isEmpty() ? "" : ", " + extra) + "}";
    }

    @Test
    void oneVersionAsAString() {
        ModDescriptor descriptor = ModDescriptor.parse(json("\"1.8.9\"", ""));
        assertTrue(descriptor.supportsMinecraft("1.8.9"));
        assertFalse(descriptor.supportsMinecraft("1.7.10"));
        assertEquals("1.8.9", descriptor.minecraftText());
    }

    @Test
    void aListIsAWhitelist() {
        ModDescriptor descriptor = ModDescriptor.parse(json("[\"1.7.10\", \"1.8.9\"]", ""));
        assertTrue(descriptor.supportsMinecraft("1.7.10"));
        assertTrue(descriptor.supportsMinecraft("1.8.9"));
        assertFalse(descriptor.supportsMinecraft("1.8.8"));
        assertEquals("1.7.10, 1.8.9", descriptor.minecraftText());
    }

    @Test
    void entriesMayBeRanges() {
        ModDescriptor descriptor = ModDescriptor.parse(json("\">=1.7.10 <1.9\"", ""));
        assertTrue(descriptor.supportsMinecraft("1.7.10"));
        assertTrue(descriptor.supportsMinecraft("1.8.9"));
        assertFalse(descriptor.supportsMinecraft("1.12.2"));
    }

    @Test
    void minecraftIsRequired() {
        assertThrows(IllegalArgumentException.class,
                () -> ModDescriptor.parse("{\"schema\": 1, \"id\": \"example\", \"version\": \"1.0.0\"}"));
        assertThrows(IllegalArgumentException.class, () -> ModDescriptor.parse(json("[]", "")));
        assertThrows(IllegalArgumentException.class, () -> ModDescriptor.parse(json("189", "")));
    }

    @Test
    void versionPartsAddToTheTopLevel() {
        ModDescriptor descriptor = ModDescriptor.parse(json("[\"1.7.10\", \"1.8.9\"]", """
                "entrypoints": {"client": ["dev.example.Client"]},
                "hooks": ["render.hud"],
                "mixins": ["mixins.example.json"],
                "accessWidener": "example.aw",
                "versions": {
                  "1.7.10": {
                    "entrypoints": {"client": ["dev.example.v1_7.Client"], "init": ["dev.example.v1_7.Init"]},
                    "hooks": ["chat.receive"],
                    "mixins": ["mixins.example.1_7_10.json"],
                    "accessWidener": "example.1_7_10.aw"
                  }
                }"""));

        ModDescriptor old = descriptor.forMinecraft("1.7.10");
        assertEquals(List.of("dev.example.Client", "dev.example.v1_7.Client"), old.entrypoints("client"));
        assertEquals(List.of("dev.example.v1_7.Init"), old.entrypoints("init"));
        assertEquals(List.of("render.hud", "chat.receive"), old.hooks());
        assertEquals(List.of("mixins.example.json", "mixins.example.1_7_10.json"), old.mixins());
        assertEquals("example.1_7_10.aw", old.accessWidener());
        assertEquals(Map.of(), old.versions());

        ModDescriptor current = descriptor.forMinecraft("1.8.9");
        assertEquals(List.of("dev.example.Client"), current.entrypoints("client"));
        assertEquals(List.of("mixins.example.json"), current.mixins());
        assertEquals("example.aw", current.accessWidener());
        assertEquals(Map.of(), current.versions());
    }

    @Test
    void aJarWithoutVersionPartsStaysAsItIs() {
        ModDescriptor descriptor = ModDescriptor.parse(json("\"1.8.9\"", "\"mixins\": [\"mixins.example.json\"]"));
        assertSame(descriptor, descriptor.forMinecraft("1.8.9"));
    }

    @Test
    void aVersionPartWithoutAnAccessWidenerKeepsTheTopLevelOne() {
        ModDescriptor descriptor = ModDescriptor.parse(json("[\"1.7.10\", \"1.8.9\"]",
                "\"accessWidener\": \"example.aw\", \"versions\": {\"1.7.10\": {\"mixins\": \"m.json\"}}"));
        assertEquals("example.aw", descriptor.forMinecraft("1.7.10").accessWidener());
        assertNull(ModDescriptor.parse(json("\"1.8.9\"", "")).forMinecraft("1.8.9").accessWidener());
    }

    @Test
    void versionPartsMustBeForListedVersions() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ModDescriptor.parse(
                json("\"1.8.9\"", "\"versions\": {\"1.7.10\": {\"mixins\": \"m.json\"}}")));
        assertTrue(e.getMessage().contains("1.7.10"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> ModDescriptor.parse(
                json("\"1.8.9\"", "\"versions\": {\"1.8.x\": {}}")));
    }

    @Test
    void versionPartsRejectFieldsTheyCantSet() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ModDescriptor.parse(
                json("\"1.8.9\"", "\"versions\": {\"1.8.9\": {\"mixin\": \"m.json\"}}")));
        assertTrue(e.getMessage().contains("mixin"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> ModDescriptor.parse(
                json("\"1.8.9\"", "\"versions\": {\"1.8.9\": {\"entrypoints\": {\"server\": \"a.B\"}}}")));
        assertThrows(IllegalArgumentException.class, () -> ModDescriptor.parse(
                json("\"1.8.9\"", "\"versions\": {\"1.8.9\": []}")));
    }
}
