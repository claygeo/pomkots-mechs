package grcmcs.minecraft.mods.pomkotsmechs.arena;

/**
 * PvE pressure applied to a royale match when the GRACE phase ends. {@link #OFF}
 * spawns nothing (the default, so an un-migrated world behaves exactly as before);
 * {@link #LIGHT} spawns one wave of hostile mobs, {@link #HEAVY} two. Persisted in
 * {@link ArenaData}; an absent or unrecognised stored value falls back to {@link #OFF}.
 */
public enum RoyalePve {
    OFF,
    LIGHT,
    HEAVY
}
