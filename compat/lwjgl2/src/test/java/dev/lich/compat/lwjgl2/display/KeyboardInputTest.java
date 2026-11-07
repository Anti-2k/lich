package dev.lich.compat.lwjgl2.display;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.input.Keyboard;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GLFW's separate key and character callbacks, turned into LWJGL 2's combined keyboard events. */
class KeyboardInputTest {
    private static final int KEY_T = 0x14;
    private static final int KEY_RETURN = 0x1C;

    @BeforeEach
    void create() {
        KeyboardInput.create();
        KeyboardInput.enableRepeatEvents(false);
    }

    private static void assertEvent(int key, char character, boolean state) {
        assertTrue(KeyboardInput.next(), "an event");
        assertEquals(key, KeyboardInput.eventKey());
        assertEquals(character, KeyboardInput.eventCharacter());
        assertEquals(state, KeyboardInput.eventState());
    }

    @Test
    void theKeyStatesAreReadableAsLwjgl2sBuffer() throws ReflectiveOperationException {
        // As RenderLib reads it.
        Field field = Keyboard.class.getDeclaredField("keyDownBuffer");
        field.setAccessible(true);
        ByteBuffer buffer = (ByteBuffer) field.get(null);

        KeyboardInput.key(GLFW.GLFW_KEY_T, 20, GLFW.GLFW_PRESS);
        assertTrue(buffer.get(KEY_T) != 0);
        KeyboardInput.key(GLFW.GLFW_KEY_T, 20, GLFW.GLFW_RELEASE);
        assertEquals(0, buffer.get(KEY_T));
    }

    @Test
    void aKeyPressCarriesTheCharacterItTyped() {
        KeyboardInput.key(GLFW.GLFW_KEY_T, 20, GLFW.GLFW_PRESS);
        KeyboardInput.character('t');
        KeyboardInput.endOfMessages();
        KeyboardInput.key(GLFW.GLFW_KEY_T, 20, GLFW.GLFW_RELEASE);

        assertEvent(KEY_T, 't', true);
        assertFalse(KeyboardInput.isKeyDown(KEY_T), "released by now");
        assertEvent(KEY_T, '\0', false);
        assertFalse(KeyboardInput.next());
    }

    @Test
    void charactersWithoutAKeyPressComeWithKeyZero() {
        KeyboardInput.key(GLFW.GLFW_KEY_ENTER, 28, GLFW.GLFW_PRESS);
        KeyboardInput.endOfMessages();
        // An IME or a dead key: no key press of its own, and too late for the one before.
        KeyboardInput.character('é');
        // Outside the basic plane: two UTF-16 characters, as LWJGL 2 delivered them on Windows.
        KeyboardInput.character(0x1F600);

        assertEvent(KEY_RETURN, '\0', true);
        assertEvent(0, 'é', true);
        assertEvent(0, Character.highSurrogate(0x1F600), true);
        assertEvent(0, Character.lowSurrogate(0x1F600), true);
        assertFalse(KeyboardInput.next());
    }

    @Test
    void repeatsAreSkippedUnlessEnabledCharactersIncluded() {
        KeyboardInput.key(GLFW.GLFW_KEY_T, 20, GLFW.GLFW_PRESS);
        KeyboardInput.character('t');
        KeyboardInput.key(GLFW.GLFW_KEY_T, 20, GLFW.GLFW_REPEAT);
        KeyboardInput.character('t');
        KeyboardInput.endOfMessages();

        assertEvent(KEY_T, 't', true);
        assertFalse(KeyboardInput.next(), "the repeat and its character are skipped");

        KeyboardInput.enableRepeatEvents(true);
        KeyboardInput.key(GLFW.GLFW_KEY_T, 20, GLFW.GLFW_REPEAT);
        KeyboardInput.character('t');
        KeyboardInput.endOfMessages();
        assertEvent(KEY_T, 't', true);
        assertTrue(KeyboardInput.eventRepeat());
    }

    @Test
    void keyStateFollowsPressesAndReleases() {
        KeyboardInput.key(GLFW.GLFW_KEY_LEFT_SHIFT, 42, GLFW.GLFW_PRESS);
        assertTrue(KeyboardInput.isKeyDown(0x2A));
        KeyboardInput.key(GLFW.GLFW_KEY_LEFT_SHIFT, 42, GLFW.GLFW_RELEASE);
        assertFalse(KeyboardInput.isKeyDown(0x2A));
        assertFalse(KeyboardInput.isKeyDown(-100), "out of range is up, not an exception");
    }

    @Test
    void keysGlfwCantNameAreDropped() {
        KeyboardInput.key(GLFW.GLFW_KEY_UNKNOWN, 0x123, GLFW.GLFW_PRESS);
        KeyboardInput.endOfMessages();
        assertFalse(KeyboardInput.next());
    }
}
