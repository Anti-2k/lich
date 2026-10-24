package dev.lich.compat.lwjgl2.display;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GLFW cursor and wheel callbacks, turned into LWJGL 2's mouse coordinates and events (no window needed). */
class MouseInputTest {
    @BeforeEach
    void create() {
        // A 200 x 100 framebuffer at twice the window coordinates, as on a HiDPI Mac.
        WindowState.update(0L, 200, 100, 100, 50);
        MouseInput.create();
        MouseInput.cursor(0, 49.5);
        MouseInput.next();
        MouseInput.takeDX();
        MouseInput.takeDY();
    }

    @AfterEach
    void destroy() {
        MouseInput.destroy();
    }

    @Test
    void positionsAreFramebufferPixelsFromTheBottomLeft() {
        MouseInput.cursor(10, 20);
        assertTrue(MouseInput.next());
        assertEquals(-1, MouseInput.eventButton());
        assertEquals(20, MouseInput.eventX());
        assertEquals(100 - 1 - 40, MouseInput.eventY());
        assertEquals(20, MouseInput.eventDX());
        assertEquals(59 - 0, MouseInput.eventDY(), "moving up the window is positive");
        assertEquals(20, MouseInput.takeDX());
        assertEquals(0, MouseInput.takeDX(), "reading the delta resets it");
    }

    @Test
    void buttonsAndTheWheel() {
        MouseInput.button(1, true);
        MouseInput.scroll(1.0);
        MouseInput.scroll(-0.5);
        MouseInput.scroll(-0.5);

        assertTrue(MouseInput.next());
        assertEquals(1, MouseInput.eventButton());
        assertTrue(MouseInput.eventButtonState());
        assertTrue(MouseInput.isButtonDown(1));
        assertTrue(MouseInput.next());
        assertEquals(120, MouseInput.eventDWheel(), "LWJGL 2 counted 120 per notch");
        assertTrue(MouseInput.next());
        assertEquals(-60, MouseInput.eventDWheel());
        assertTrue(MouseInput.next());
        assertEquals(-60, MouseInput.eventDWheel());
        assertFalse(MouseInput.next());
        assertEquals(0, MouseInput.takeDWheel());
    }

    @Test
    void aGrabbedMouseMovesByDeltasAndStaysClippedToTheWindow() {
        MouseInput.setGrabbed(true);
        MouseInput.cursor(-500, 49.5);
        assertTrue(MouseInput.next());
        assertEquals(0, MouseInput.eventX(), "clipped");
        assertEquals(-1000, MouseInput.eventDX());
        MouseInput.cursor(-450, 49.5);
        assertTrue(MouseInput.next());
        assertEquals(0, MouseInput.eventX(), "still left of the window: the unclipped position counts");
        assertEquals(100, MouseInput.eventDX());
    }
}
