package dev.lich.harness.driver;

/**
 * A step failed because of what the game did: it left the world, showed an error screen, didn't load in time, or threw
 * from a call the driver made into it. Any other exception in the driver is the driver's own bug, which the harness
 * reports as such instead of blaming the pack or Lich.
 */
final class StepFailed extends RuntimeException {
    private static final long serialVersionUID = 1L;

    StepFailed(String message) {
        super(message);
    }

    StepFailed(String message, Throwable cause) {
        super(message, cause);
    }
}
