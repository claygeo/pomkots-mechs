package grcmcs.minecraft.mods.pomkotsmechs.config.datapack;

import com.google.common.reflect.TypeToken;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import grcmcs.minecraft.mods.pomkotsmechs.PomkotsMechs;
import grcmcs.minecraft.mods.pomkotsmechs.config.datapack.raid.EventDefinition;
import grcmcs.minecraft.mods.pomkotsmechs.config.datapack.raid.RaidDefinition;
import grcmcs.minecraft.mods.pomkotsmechs.config.datapack.raid.SpawnTarget;
import grcmcs.minecraft.mods.pomkotsmechs.config.datapack.raid.WaveDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static net.minecraft.util.datafix.fixes.BlockEntitySignTextStrictJsonFix.GSON;

public class PomkotsDataPackManager {
    private static final PomkotsDataPackManager singleton = new PomkotsDataPackManager();
    public static PomkotsDataPackManager getInstance() {
        return singleton;
    }

    private final PomkotsDataPack dataPackServer = new PomkotsDataPack();
    private PomkotsDataPack dataPackClient = new PomkotsDataPack();

    public PomkotsDataPack getDataPack() {
        if (Platform.getEnvironment() == Env.SERVER) {
            return dataPackServer;
        } else {
            return dataPackClient;
        }
    }

    public void loadDataPack(ResourceManager manager) {
        // Never let a malformed datapack propagate out of ServerStartingEvent and
        // abort the server boot. Every stage is defensive; raid/chest data is built
        // into validated snapshots first and only swapped into the live maps after a
        // clean pass, so a bad file degrades gracefully instead of clearing content.
        try {
            dataPackServer.reset();

            loadAllPartsData(manager);
            loadAllEnemyData(manager);

            Map<String, RaidDefinition> raidSnapshot = loadRaidSnapshot(manager);
            Map<String, PomkotsDataPack.ChestData> chestSnapshot = loadChestSnapshot(manager, raidSnapshot);

            // Swap live maps only after both passes complete successfully.
            dataPackServer.replaceRaidData(raidSnapshot);
            dataPackServer.replaceChestData(chestSnapshot);

            if (Platform.getEnvironment() == Env.CLIENT && !dataPackServer.isEmpty()) {
                dataPackClient = dataPackServer;
            }

            PomkotsMechs.LOGGER.info("DP:" + dataPackServer);
        } catch (Throwable t) {
            PomkotsMechs.LOGGER.error("Fatal error while loading Pomkots datapack; keeping whatever loaded cleanly", t);
        }
    }

    private void loadAllPartsData(ResourceManager manager) {
        ResourceLocation path = new ResourceLocation(PomkotsMechs.MODID, "parts.json");
        List<Resource> resources;

        try {
            resources = manager.getResourceStack(path);
        } catch (Exception e) {
            PomkotsMechs.LOGGER.error("Failed to load resource stack:" + path, e);
            return;
        }

        for (Resource resource : resources) {
            try (InputStream stream = resource.open()) {
                JsonObject json = JsonParser.parseReader(new InputStreamReader(stream)).getAsJsonObject();

                if (json != null) {
                    loadRootPartsData(json);
                } else {
                    PomkotsMechs.LOGGER.error("No parts on a parts pack:" + resource);
                }
            } catch (IOException e) {
                PomkotsMechs.LOGGER.error("Failed to load a parts pack:" + resource, e);
            }
        }
    }

    private void loadRootPartsData(JsonObject json) {
        var root = json.get("mech_parts").getAsJsonObject();
        if (root == null) {
            return;
        }

        for (var item: root.asMap().entrySet()) {
            loadPartsData(item.getValue(), item.getKey());
        }
    }

    private void loadPartsData(JsonElement itemEle, String itemName) {
        var typeEle = itemEle.getAsJsonObject().get("type");

        if (typeEle != null && "parts".equals(typeEle.getAsString())) {
            var obj = itemEle.getAsJsonObject();

            registerPartsDataSingle(itemName + "head", obj.get("head"));
            registerPartsDataSingle(itemName + "body", obj.get("body"));
            registerPartsDataSingle(itemName + "arm", obj.get("arm"));
            registerPartsDataSingle(itemName + "legs", obj.get("legs"));

        } else {
            registerPartsDataSingle(itemName, itemEle);
        }
    }

    private void registerPartsDataSingle(String itemName, JsonElement itemRootEle) {
        if (itemRootEle == null) {
            return;
        }

        var itemRoot = itemRootEle.getAsJsonObject();

        PomkotsDataPack.PartsData data = new PomkotsDataPack.PartsData();
        data.id = itemName;
        data.weight = getInt(itemRoot, "weight");
        data.description = getString(itemRoot, "description");

        var levelArrayEle = itemRoot.get("level_param");

        if (levelArrayEle != null) {
            var levelArray = levelArrayEle.getAsJsonArray();

            for (var levelParamsEle: levelArray.asList()) {
                var levelParamsObj = levelParamsEle.getAsJsonObject();
                var levelData = new PomkotsDataPack.LevelData();

                levelData.durability = getInt(levelParamsObj, "durability");
                levelData.maxWeight = getInt(levelParamsObj, "max_weight");
                levelData.speedModifier = getFloat(levelParamsObj, "speed_modifier");
                levelData.jumpModifier = getFloat(levelParamsObj, "jump_modifier");
                levelData.damage = getFloat(levelParamsObj, "damage");
                levelData.missileMaxNum = getInt(levelParamsObj, "missile_max_num");
                levelData.missileLockInterval = getFloat(levelParamsObj, "missile_lock_interval");
                levelData.maxEnergy = getInt(levelParamsObj, "max_energy");
                levelData.energyChargePerTick = getInt(levelParamsObj, "energy_charge_per_tick");
                levelData.workSecPerFuel = getInt(levelParamsObj, "work_sec_per_fuel");
                levelData.energyConsumeEvasion = getInt(levelParamsObj, "energy_consume_evasion");
                levelData.energyConsumeVertical = getInt(levelParamsObj, "energy_consume_vertical");
                levelData.speedModifierEvasion = getFloat(levelParamsObj, "speed_modifier_evasion");
                levelData.speedModifierVertical = getFloat(levelParamsObj, "speed_modifier_vertical");
                levelData.bulletsPerMagazine = getInt(levelParamsObj, "bullets_per_magazine");
                levelData.energy = getInt(levelParamsObj, "energy");

                data.levels.add(levelData);
            }
        }

        var recipeArrayEle = itemRoot.get("recipes");
        if (recipeArrayEle != null) {
            var recipeArray = recipeArrayEle.getAsJsonArray();

            for (var recipeEle: recipeArray.asList()) {
                var recipe = recipeEle.getAsJsonObject().asMap();

                List<PomkotsDataPack.SerializablePair<String, Integer>> rec = new ArrayList<>();
                for (var material: recipe.entrySet()) {
                    rec.add(new PomkotsDataPack.SerializablePair<String, Integer>(material.getKey(), material.getValue().getAsInt()));
                }
                data.recipes.add(rec);
            }
        }

        dataPackServer.addPartsData(itemName, data);
    }


    private void loadAllEnemyData(ResourceManager manager) {
        ResourceLocation path = new ResourceLocation(PomkotsMechs.MODID, "enemies.json");
        List<Resource> resources;

        try {
            resources = manager.getResourceStack(path);
        } catch (Exception e) {
            PomkotsMechs.LOGGER.error("Failed to load resource stack:" + path, e);
            return;
        }

        for (Resource resource : resources) {
            try (InputStream stream = resource.open()) {
                JsonObject json = JsonParser.parseReader(new InputStreamReader(stream)).getAsJsonObject();

                if (json != null) {
                    loadRootEnemyData(json);
                } else {
                    PomkotsMechs.LOGGER.error("No enemy on a enemies pack:" + resource);
                }
            } catch (IOException e) {
                PomkotsMechs.LOGGER.error("Failed to load a enemies pack:" + resource, e);
            }
        }
    }

    private void loadRootEnemyData(JsonObject json) {
        var root = json.get("enemy_data").getAsJsonObject();
        if (root == null) {
            return;
        }

        for (var enemy: root.asMap().entrySet()) {
            loadEnemyData(enemy.getValue(), enemy.getKey());
        }
    }

    private void loadEnemyData(JsonElement enemyEle, String enemyName) {
        PomkotsDataPack.EnemyData data = new PomkotsDataPack.EnemyData();

        data.id = enemyName;
        data.speed = getFloat(enemyEle, "speed");
        data.maxStepUp = getFloat(enemyEle, "max_step_up");
        data.knockBackResistance = getFloat(enemyEle, "knock_back_resistance");
        data.followRange = getFloat(enemyEle, "follow_range");
        data.health = getInt(enemyEle, "health");
        data.baseDamageModifier = getFloat(enemyEle, "base_damage_modifier");
        data.explosionDamageModifier = getFloat(enemyEle, "explosion_damage_modifier");
        data.armor = getFloat(enemyEle, "armor");
        data.armorToughness = getFloat(enemyEle, "armor_toughness");
        data.bulletDamage = getFloat(enemyEle, "bullet_damage");
        data.bulletSpeed = getFloat(enemyEle, "bullet_speed");
        data.missileDamage = getFloat(enemyEle, "missile_damage");
        data.missileSpeed = getFloat(enemyEle, "missile_speed");
        data.grenadeDamage = getFloat(enemyEle, "grenade_damage");
        data.grenadeSpeed = getFloat(enemyEle, "grenade_speed");
        data.grenadeExplosionScale = getFloat(enemyEle, "grenade_explosion_scale");
        data.meleeDamage = getFloat(enemyEle, "melee_damage");
        data.meleeSpeed = getFloat(enemyEle, "melee_speed");
        data.laserDamage = getFloat(enemyEle, "laser_damage");
        data.laserSpeed = getFloat(enemyEle, "laser_speed");
        data.exAttack1Damage = getFloat(enemyEle, "ex_attack_1_damage");
        data.exAttack1Speed = getFloat(enemyEle, "ex_attack_1_speed");
        data.exAttack2Damage = getFloat(enemyEle, "ex_attack_2_damage");
        data.exAttack2Speed = getFloat(enemyEle, "ex_attack_2_speed");
        data.exAttack3Damage = getFloat(enemyEle, "ex_attack_3_damage");
        data.exAttack3Speed = getFloat(enemyEle, "ex_attack_3_speed");

        dataPackServer.addEnemyData(enemyName, data);
    }

    private int getInt(JsonElement parent, String name) {
        if (parent == null) {
            return 0;
        } else {
            var el = parent.getAsJsonObject().get(name);
            if (el == null) {
                return 0;
            }
            return el.getAsInt();
        }
    }

    private float getFloat(JsonElement parent, String name) {
        if (parent == null) {
            return 0F;
        } else {
            var el = parent.getAsJsonObject().get(name);
            if (el == null) {
                return 0F;
            }
            return el.getAsFloat();
        }
    }

    private String getString(JsonElement parent, String name) {
        if (parent == null) {
            return "";
        } else {
            var el = parent.getAsJsonObject().get(name);
            if (el == null) {
                return "";
            }
            return el.getAsString();
        }
    }

    private static final int MAX_SPAWN_COORD = 512;

    /** Known raid types (mirror of RaidControllerEntity.RaidType ids). */
    private static final Set<String> KNOWN_RAID_TYPES = Set.of(
            "defense", "survive", "sweep", "sweep_boss_box", "activate");

    /** Known per-event spawn types. */
    private static final Set<String> KNOWN_EVENT_TYPES = Set.of("spawn", "spawn_random");

    private Map<String, RaidDefinition> loadRaidSnapshot(ResourceManager manager) {
        Map<String, RaidDefinition> snapshot = new HashMap<>();

        ResourceLocation path = new ResourceLocation(PomkotsMechs.MODID, "raid.json");
        List<Resource> resources;
        try {
            resources = manager.getResourceStack(path);
        } catch (Exception e) {
            PomkotsMechs.LOGGER.error("Failed to load resource stack:" + path, e);
            return snapshot;
        }

        for (Resource resource : resources) {
            Map<String, RaidDefinition> allRd;
            try (InputStream stream = resource.open()) {
                Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8);
                allRd = GSON.fromJson(reader, new TypeToken<Map<String, RaidDefinition>>(){}.getType());
            } catch (Exception e) {
                // Malformed JSON / Gson error: skip this whole resource, keep going.
                PomkotsMechs.LOGGER.error("Failed to parse a raid pack (skipping):" + resource, e);
                continue;
            }
            if (allRd == null) continue;

            for (var entry : allRd.entrySet()) {
                String key = entry.getKey();
                RaidDefinition rd = entry.getValue();
                try {
                    if (key == null || rd == null) {
                        PomkotsMechs.LOGGER.error("Raid entry with null key/value skipped in " + resource);
                        continue;
                    }
                    if (!validateRaid(key, rd)) {
                        // validateRaid already logged the reason
                        continue;
                    }
                    buildTimeline(rd);
                    snapshot.put(key, rd);
                } catch (Exception ex) {
                    PomkotsMechs.LOGGER.error("Skipping invalid raid '" + key + "' in " + resource, ex);
                }
            }
        }
        return snapshot;
    }

    private Map<String, PomkotsDataPack.ChestData> loadChestSnapshot(ResourceManager manager, Map<String, RaidDefinition> raidSnapshot) {
        Map<String, PomkotsDataPack.ChestData> snapshot = new HashMap<>();

        ResourceLocation path = new ResourceLocation(PomkotsMechs.MODID, "chest.json");
        List<Resource> resources;
        try {
            resources = manager.getResourceStack(path);
        } catch (Exception e) {
            PomkotsMechs.LOGGER.error("Failed to load resource stack:" + path, e);
            return snapshot;
        }

        for (Resource resource : resources) {
            Map<String, PomkotsDataPack.ChestData> allCd;
            try (InputStream stream = resource.open()) {
                Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8);
                allCd = GSON.fromJson(reader, new TypeToken<Map<String, PomkotsDataPack.ChestData>>(){}.getType());
            } catch (Exception e) {
                PomkotsMechs.LOGGER.error("Failed to parse a chest pack (skipping):" + resource, e);
                continue;
            }
            if (allCd == null) continue;

            for (var entry : allCd.entrySet()) {
                String key = entry.getKey();
                PomkotsDataPack.ChestData cd = entry.getValue();
                try {
                    if (key == null || cd == null) {
                        PomkotsMechs.LOGGER.error("Chest entry with null key/value skipped in " + resource);
                        continue;
                    }
                    if (!validateChest(key, cd, raidSnapshot)) {
                        continue;
                    }
                    snapshot.put(key, cd);
                } catch (Exception ex) {
                    PomkotsMechs.LOGGER.error("Skipping invalid chest '" + key + "' in " + resource, ex);
                }
            }
        }
        return snapshot;
    }

    private void buildTimeline(RaidDefinition rd) {
        if (rd.waves == null) return;
        for (WaveDefinition wd : rd.waves) {
            if (wd == null || wd.events == null) continue;
            for (EventDefinition ed : wd.events) {
                if (ed != null && "time".equals(ed.trigger_type)) {
                    if (wd.timeline == null) {
                        wd.timeline = new HashMap<>();
                    }
                    wd.timeline.put(ed.trigger_tick, ed);
                }
            }
        }
    }

    /** Semantic validation; logs and returns false on the first problem. */
    private boolean validateRaid(String key, RaidDefinition rd) {
        if (rd.type == null || !KNOWN_RAID_TYPES.contains(rd.type)) {
            PomkotsMechs.LOGGER.error("Raid '{}' has unknown type '{}'; skipping.", key, rd.type);
            return false;
        }
        if (rd.waves == null || rd.waves.isEmpty()) {
            PomkotsMechs.LOGGER.error("Raid '{}' has no waves; skipping.", key);
            return false;
        }
        for (int w = 0; w < rd.waves.size(); w++) {
            WaveDefinition wd = rd.waves.get(w);
            if (wd == null) {
                PomkotsMechs.LOGGER.error("Raid '{}' wave {} is null; skipping raid.", key, w);
                return false;
            }
            if (wd.duration_ticks <= 0) {
                PomkotsMechs.LOGGER.error("Raid '{}' wave {} has non-positive duration {}; skipping raid.", key, w, wd.duration_ticks);
                return false;
            }
            if (wd.events == null) continue; // waves may legitimately have no events (e.g. intervals / activate)
            for (EventDefinition ed : wd.events) {
                if (ed == null) {
                    PomkotsMechs.LOGGER.error("Raid '{}' wave {} has a null event; skipping raid.", key, w);
                    return false;
                }
                if (ed.type == null || !KNOWN_EVENT_TYPES.contains(ed.type)) {
                    PomkotsMechs.LOGGER.error("Raid '{}' wave {} event '{}' has unknown type '{}'; skipping raid.", key, w, ed.id, ed.type);
                    return false;
                }
                if (ed.spawn_targets == null || ed.spawn_targets.isEmpty()) {
                    PomkotsMechs.LOGGER.error("Raid '{}' wave {} event '{}' has empty spawn_targets; skipping raid.", key, w, ed.id);
                    return false;
                }
                for (SpawnTarget st : ed.spawn_targets) {
                    if (st == null || st.mob_type == null || resolveEntityType(st.mob_type) == null) {
                        PomkotsMechs.LOGGER.error("Raid '{}' wave {} event '{}' references unresolvable mob '{}'; skipping raid.",
                                key, w, ed.id, st == null ? null : st.mob_type);
                        return false;
                    }
                    if ("spawn".equals(ed.type) && st.position != null && !isSanePosition(st.position)) {
                        PomkotsMechs.LOGGER.error("Raid '{}' wave {} event '{}' has out-of-range position '{}'; skipping raid.",
                                key, w, ed.id, st.position);
                        return false;
                    }
                }
                if ("spawn_random".equals(ed.type) && ed.amount <= 0) {
                    PomkotsMechs.LOGGER.error("Raid '{}' wave {} event '{}' has non-positive amount {}; skipping raid.", key, w, ed.id, ed.amount);
                    return false;
                }
            }
        }
        return true;
    }

    private boolean validateChest(String key, PomkotsDataPack.ChestData cd, Map<String, RaidDefinition> raidSnapshot) {
        if (cd.type == null || cd.type.isEmpty()) {
            PomkotsMechs.LOGGER.error("Chest '{}' has empty type; skipping.", key);
            return false;
        }
        // A "raid" chest must point at a raid that survived validation.
        if ("raid".equals(cd.type)) {
            if (cd.raid_id == null || !raidSnapshot.containsKey(cd.raid_id)) {
                PomkotsMechs.LOGGER.error("Chest '{}' references missing/invalid raid_id '{}'; skipping.", key, cd.raid_id);
                return false;
            }
        }
        return true;
    }

    private boolean isSanePosition(String position) {
        try {
            String[] parts = position.trim().split("[,\\s]+");
            if (parts.length != 3) return false;
            for (String p : parts) {
                int v = Integer.parseInt(p);
                if (Math.abs(v) > MAX_SPAWN_COORD) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Resolve an entity id against the mod's registry first, then the built-in one. */
    private net.minecraft.world.entity.EntityType<?> resolveEntityType(String id) {
        try {
            var supplier = grcmcs.minecraft.mods.pomkotsmechs.util.Utils.getEntityType(id);
            if (supplier != null) return supplier.get();
            ResourceLocation rl = new ResourceLocation(id);
            return net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getOptional(rl).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    public void serializeServerData(OutputStream outputStream) throws IOException {
        ObjectOutputStream objectOutputStream = new ObjectOutputStream(outputStream);

        objectOutputStream.writeObject(dataPackServer);
        objectOutputStream.flush();

        objectOutputStream.close();
    }

    public PomkotsDataPack deSerialize(InputStream inputStream) throws IOException, ClassNotFoundException {

        ObjectInputStream objectInputStream = new ObjectInputStream(inputStream);

        dataPackClient = (PomkotsDataPack) objectInputStream.readObject();

        objectInputStream.close();

        return dataPackClient;
    }
}
