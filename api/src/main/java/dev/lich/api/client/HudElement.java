package dev.lich.api.client;

/**
 * The parts of the in-game HUD that {@code HudEvents} fire around, Forge's element types plus the title and
 * the action bar. {@link #ALL} wraps the whole HUD.
 */
public enum HudElement {
    ALL, HELMET, PORTAL, CROSSHAIRS, BOSSHEALTH, ARMOR, HEALTH, FOOD, AIR, HOTBAR, EXPERIENCE, HEALTHMOUNT, JUMPBAR,
    CHAT, PLAYER_LIST, DEBUG,
    /** The big title and subtitle in the middle of the screen (1.8.9; 1.7.10 has no titles). */
    TITLE,
    /** The text above the hotbar (action bar messages, "Now playing" for records; on 1.7.10 only the latter). */
    ACTION_BAR
}
