package grcmcs.minecraft.mods.pomkotsmechs.arena;

/**
 * Which match flavour the arena runs. DUEL is the original mech-per-fighter
 * arena; ROYALE is a hunger-games scramble where mechs are scattered gear and
 * elimination is by player death only; SOLO is the direct one-player combat
 * loop driven by {@link SpawnDirector}. Persisted in {@link ArenaData}; when the
 * stored value is absent or unrecognised the arena falls back to {@link #DUEL},
 * so DUEL behaviour is exactly what an un-migrated world sees.
 */
public enum Mode {
    DUEL,
    ROYALE,
    SOLO
}
