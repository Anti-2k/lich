package org.lwjgl.input;

import dev.lich.compat.lwjgl2.display.KeyboardInput;
import org.lwjgl.LWJGLException;
import org.lwjgl.opengl.Display;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * LWJGL 2's keyboard, on GLFW (see {@link KeyboardInput} for how key presses and their characters become
 * one event). Key codes, names and the exceptions for using it before {@link #create} are LWJGL 2's.
 */
public class Keyboard {
    /** LWJGL 2's private field of this name. RenderLib reflects on it to read key states from any thread. */
    @SuppressWarnings("unused")
    private static final ByteBuffer keyDownBuffer = KeyboardInput.keyDownBuffer();

    public static final int EVENT_SIZE = 4 + 1 + 4 + 8 + 1;
    public static final int CHAR_NONE = '\0';

    public static final int KEY_NONE = 0x00;
    public static final int KEY_ESCAPE = 0x01;
    public static final int KEY_1 = 0x02;
    public static final int KEY_2 = 0x03;
    public static final int KEY_3 = 0x04;
    public static final int KEY_4 = 0x05;
    public static final int KEY_5 = 0x06;
    public static final int KEY_6 = 0x07;
    public static final int KEY_7 = 0x08;
    public static final int KEY_8 = 0x09;
    public static final int KEY_9 = 0x0A;
    public static final int KEY_0 = 0x0B;
    public static final int KEY_MINUS = 0x0C;
    public static final int KEY_EQUALS = 0x0D;
    public static final int KEY_BACK = 0x0E;
    public static final int KEY_TAB = 0x0F;
    public static final int KEY_Q = 0x10;
    public static final int KEY_W = 0x11;
    public static final int KEY_E = 0x12;
    public static final int KEY_R = 0x13;
    public static final int KEY_T = 0x14;
    public static final int KEY_Y = 0x15;
    public static final int KEY_U = 0x16;
    public static final int KEY_I = 0x17;
    public static final int KEY_O = 0x18;
    public static final int KEY_P = 0x19;
    public static final int KEY_LBRACKET = 0x1A;
    public static final int KEY_RBRACKET = 0x1B;
    public static final int KEY_RETURN = 0x1C;
    public static final int KEY_LCONTROL = 0x1D;
    public static final int KEY_A = 0x1E;
    public static final int KEY_S = 0x1F;
    public static final int KEY_D = 0x20;
    public static final int KEY_F = 0x21;
    public static final int KEY_G = 0x22;
    public static final int KEY_H = 0x23;
    public static final int KEY_J = 0x24;
    public static final int KEY_K = 0x25;
    public static final int KEY_L = 0x26;
    public static final int KEY_SEMICOLON = 0x27;
    public static final int KEY_APOSTROPHE = 0x28;
    public static final int KEY_GRAVE = 0x29;
    public static final int KEY_LSHIFT = 0x2A;
    public static final int KEY_BACKSLASH = 0x2B;
    public static final int KEY_Z = 0x2C;
    public static final int KEY_X = 0x2D;
    public static final int KEY_C = 0x2E;
    public static final int KEY_V = 0x2F;
    public static final int KEY_B = 0x30;
    public static final int KEY_N = 0x31;
    public static final int KEY_M = 0x32;
    public static final int KEY_COMMA = 0x33;
    public static final int KEY_PERIOD = 0x34;
    public static final int KEY_SLASH = 0x35;
    public static final int KEY_RSHIFT = 0x36;
    public static final int KEY_MULTIPLY = 0x37;
    public static final int KEY_LMENU = 0x38;
    public static final int KEY_SPACE = 0x39;
    public static final int KEY_CAPITAL = 0x3A;
    public static final int KEY_F1 = 0x3B;
    public static final int KEY_F2 = 0x3C;
    public static final int KEY_F3 = 0x3D;
    public static final int KEY_F4 = 0x3E;
    public static final int KEY_F5 = 0x3F;
    public static final int KEY_F6 = 0x40;
    public static final int KEY_F7 = 0x41;
    public static final int KEY_F8 = 0x42;
    public static final int KEY_F9 = 0x43;
    public static final int KEY_F10 = 0x44;
    public static final int KEY_NUMLOCK = 0x45;
    public static final int KEY_SCROLL = 0x46;
    public static final int KEY_NUMPAD7 = 0x47;
    public static final int KEY_NUMPAD8 = 0x48;
    public static final int KEY_NUMPAD9 = 0x49;
    public static final int KEY_SUBTRACT = 0x4A;
    public static final int KEY_NUMPAD4 = 0x4B;
    public static final int KEY_NUMPAD5 = 0x4C;
    public static final int KEY_NUMPAD6 = 0x4D;
    public static final int KEY_ADD = 0x4E;
    public static final int KEY_NUMPAD1 = 0x4F;
    public static final int KEY_NUMPAD2 = 0x50;
    public static final int KEY_NUMPAD3 = 0x51;
    public static final int KEY_NUMPAD0 = 0x52;
    public static final int KEY_DECIMAL = 0x53;
    public static final int KEY_F11 = 0x57;
    public static final int KEY_F12 = 0x58;
    public static final int KEY_F13 = 0x64;
    public static final int KEY_F14 = 0x65;
    public static final int KEY_F15 = 0x66;
    public static final int KEY_F16 = 0x67;
    public static final int KEY_F17 = 0x68;
    public static final int KEY_F18 = 0x69;
    public static final int KEY_KANA = 0x70;
    public static final int KEY_F19 = 0x71;
    public static final int KEY_CONVERT = 0x79;
    public static final int KEY_NOCONVERT = 0x7B;
    public static final int KEY_YEN = 0x7D;
    public static final int KEY_NUMPADEQUALS = 0x8D;
    public static final int KEY_CIRCUMFLEX = 0x90;
    public static final int KEY_AT = 0x91;
    public static final int KEY_COLON = 0x92;
    public static final int KEY_UNDERLINE = 0x93;
    public static final int KEY_KANJI = 0x94;
    public static final int KEY_STOP = 0x95;
    public static final int KEY_AX = 0x96;
    public static final int KEY_UNLABELED = 0x97;
    public static final int KEY_NUMPADENTER = 0x9C;
    public static final int KEY_RCONTROL = 0x9D;
    public static final int KEY_SECTION = 0xA7;
    public static final int KEY_NUMPADCOMMA = 0xB3;
    public static final int KEY_DIVIDE = 0xB5;
    public static final int KEY_SYSRQ = 0xB7;
    public static final int KEY_RMENU = 0xB8;
    public static final int KEY_FUNCTION = 0xC4;
    public static final int KEY_PAUSE = 0xC5;
    public static final int KEY_HOME = 0xC7;
    public static final int KEY_UP = 0xC8;
    public static final int KEY_PRIOR = 0xC9;
    public static final int KEY_LEFT = 0xCB;
    public static final int KEY_RIGHT = 0xCD;
    public static final int KEY_END = 0xCF;
    public static final int KEY_DOWN = 0xD0;
    public static final int KEY_NEXT = 0xD1;
    public static final int KEY_INSERT = 0xD2;
    public static final int KEY_DELETE = 0xD3;
    public static final int KEY_CLEAR = 0xDA;
    public static final int KEY_LMETA = 0xDB;
    /** @deprecated LWJGL 2's old name for {@link #KEY_LMETA}. */
    @Deprecated
    public static final int KEY_LWIN = 0xDB;
    public static final int KEY_RMETA = 0xDC;
    /** @deprecated LWJGL 2's old name for {@link #KEY_RMETA}. */
    @Deprecated
    public static final int KEY_RWIN = 0xDC;
    public static final int KEY_APPS = 0xDD;
    public static final int KEY_POWER = 0xDE;
    public static final int KEY_SLEEP = 0xDF;

    public static final int KEYBOARD_SIZE = 256;

    private static final String[] KEY_NAMES = new String[KEYBOARD_SIZE];
    private static final Map<String, Integer> KEY_INDICES = new HashMap<>(253);
    private static int keyCount;

    static {
        // The names are the constants' own, as LWJGL 2 built them; the deprecated *WIN aliases don't count.
        for (Field field : Keyboard.class.getFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers) && field.getType() == int.class
                    && field.getName().startsWith("KEY_") && !field.getName().endsWith("WIN")) {
                try {
                    int key = field.getInt(null);
                    String name = field.getName().substring(4);
                    KEY_NAMES[key] = name;
                    KEY_INDICES.put(name, key);
                    keyCount++;
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    public Keyboard() {
    }

    public static void create() throws LWJGLException {
        if (!Display.isCreated()) {
            throw new IllegalStateException("Display must be created.");
        }
        KeyboardInput.create();
    }

    public static boolean isCreated() {
        return KeyboardInput.isCreated();
    }

    public static void destroy() {
        KeyboardInput.destroy();
    }

    private static void checkCreated(String what) {
        if (!KeyboardInput.isCreated()) {
            throw new IllegalStateException("Keyboard must be created before you can " + what);
        }
    }

    /** Events arrive while {@code Display} processes messages; there's nothing left to fetch here. */
    public static void poll() {
        checkCreated("poll the device");
    }

    public static boolean isKeyDown(int key) {
        checkCreated("query key state");
        return KeyboardInput.isKeyDown(key);
    }

    public static synchronized String getKeyName(int key) {
        return key >= 0 && key < KEY_NAMES.length ? KEY_NAMES[key] : null;
    }

    public static synchronized int getKeyIndex(String keyName) {
        Integer index = KEY_INDICES.get(keyName);
        return index == null ? KEY_NONE : index;
    }

    public static int getNumKeyboardEvents() {
        checkCreated("read events");
        return KeyboardInput.eventCount();
    }

    public static boolean next() {
        checkCreated("read events");
        return KeyboardInput.next();
    }

    public static void enableRepeatEvents(boolean enable) {
        KeyboardInput.enableRepeatEvents(enable);
    }

    public static boolean areRepeatEventsEnabled() {
        return KeyboardInput.areRepeatEventsEnabled();
    }

    public static int getKeyCount() {
        return keyCount;
    }

    public static char getEventCharacter() {
        return KeyboardInput.eventCharacter();
    }

    public static int getEventKey() {
        return KeyboardInput.eventKey();
    }

    public static boolean getEventKeyState() {
        return KeyboardInput.eventState();
    }

    public static long getEventNanoseconds() {
        return KeyboardInput.eventNanoseconds();
    }

    public static boolean isRepeatEvent() {
        return KeyboardInput.eventRepeat();
    }
}
