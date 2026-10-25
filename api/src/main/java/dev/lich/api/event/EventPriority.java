package dev.lich.api.event;

/** When a listener runs relative to the others on the same event: {@link #HIGHEST} first. */
public enum EventPriority {
    HIGHEST, HIGH, NORMAL, LOW, LOWEST
}
