package grcmcs.minecraft.mods.pomkotsmechs.arena;

/**
 * Lifecycle of a single arena match. Advanced by the server tick loop in
 * {@link ArenaManager}: IDLE -> COUNTDOWN -> ACTIVE -> ENDING -> IDLE.
 */
public enum ArenaState {
    IDLE,
    COUNTDOWN,
    ACTIVE,
    ENDING
}
