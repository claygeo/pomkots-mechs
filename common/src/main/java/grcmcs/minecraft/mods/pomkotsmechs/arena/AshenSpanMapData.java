package grcmcs.minecraft.mods.pomkotsmechs.arena;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * World-baked identity for the bounded Sector 01 template.
 *
 * <p>The candidate world generator writes this SavedData file after auditing the actual
 * region/entity/POI chunk inventory. Mission startup reads it without loading distant
 * chunks, then also checks physical sentinels in the already-loaded Garage chunk. A
 * config JSON or asset JAR alone is deliberately insufficient to identify a world.</p>
 */
public final class AshenSpanMapData extends SavedData {
    public static final String DATA_NAME = "mecharena_sector01_contract";
    public static final String MAP_ID = "cold_ruin_sector_01";
    public static final String MISSION_ID = "operation_ashen_span";
    public static final int MAP_VERSION = 1;
    public static final long WORLD_SEED = 0x415348454E535041L; // ASCII "ASHENSPA"
    public static final int ORIGIN_X = 0;
    public static final int ORIGIN_Z = 0;
    public static final int CONTENT_MIN_CHUNK_X = -11;
    public static final int CONTENT_MAX_CHUNK_X = 10;
    public static final int CONTENT_MIN_CHUNK_Z = -7;
    public static final int CONTENT_MAX_CHUNK_Z = 6;
    public static final int SAFETY_MIN_CHUNK_X = -17;
    public static final int SAFETY_MAX_CHUNK_X = 16;
    public static final int SAFETY_MIN_CHUNK_Z = -10;
    public static final int SAFETY_MAX_CHUNK_Z = 9;
    public static final int EXPECTED_CONTENT_CHUNKS = 308;
    public static final int EXPECTED_SAFETY_CHUNKS = 680;
    public static final String EXPECTED_PROFILE_SHA256 =
            "d0a3f587d39350e0c66fd59164ce500146406ce55bd75d582912905eb5c1e868";
    public static final String EXPECTED_ASSET_SHA256 =
            "e1ac026bc07966803c5f3afcf455a0b6b7f924f4131c52600355944a5cbbefa9";
    public static final String EXPECTED_CONTRACT_SHA256 =
            "60d315c55e9382cd11dd98cc1b5c4b78e97cefe7eb7f150a17724987f109f92e";

    private String mapId = "";
    private String missionId = "";
    private int mapVersion;
    private long worldSeed;
    private int originX;
    private int originZ;
    private int contentChunkCount;
    private int safetyChunkCount;
    private String safetyChunkDigest = "";
    private String markerFingerprint = "";
    private String profileSha256 = "";
    private String assetSha256 = "";
    private String contractSha256 = "";

    public AshenSpanMapData() {
    }

    static AshenSpanMapData expectedForTests() {
        AshenSpanMapData data = new AshenSpanMapData();
        data.mapId = MAP_ID;
        data.missionId = MISSION_ID;
        data.mapVersion = MAP_VERSION;
        data.worldSeed = WORLD_SEED;
        data.originX = ORIGIN_X;
        data.originZ = ORIGIN_Z;
        data.contentChunkCount = EXPECTED_CONTENT_CHUNKS;
        data.safetyChunkCount = EXPECTED_SAFETY_CHUNKS;
        data.safetyChunkDigest = expectedSafetyChunkDigest();
        data.markerFingerprint = AshenSpanMapContract.EXPECTED_MARKER_FINGERPRINT;
        data.profileSha256 = EXPECTED_PROFILE_SHA256;
        data.assetSha256 = EXPECTED_ASSET_SHA256;
        data.contractSha256 = EXPECTED_CONTRACT_SHA256;
        return data;
    }

    public static AshenSpanMapData load(CompoundTag tag) {
        AshenSpanMapData data = new AshenSpanMapData();
        data.mapId = tag.getString("mapId");
        data.missionId = tag.getString("missionId");
        data.mapVersion = tag.getInt("mapVersion");
        data.worldSeed = tag.getLong("worldSeed");
        data.originX = tag.getInt("originX");
        data.originZ = tag.getInt("originZ");
        data.contentChunkCount = tag.getInt("contentChunkCount");
        data.safetyChunkCount = tag.getInt("safetyChunkCount");
        data.safetyChunkDigest = tag.getString("safetyChunkDigest");
        data.markerFingerprint = tag.getString("markerFingerprint");
        data.profileSha256 = tag.getString("profileSha256");
        data.assetSha256 = tag.getString("assetSha256");
        data.contractSha256 = tag.getString("contractSha256");
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putString("mapId", mapId);
        tag.putString("missionId", missionId);
        tag.putInt("mapVersion", mapVersion);
        tag.putLong("worldSeed", worldSeed);
        tag.putInt("originX", originX);
        tag.putInt("originZ", originZ);
        tag.putInt("contentChunkCount", contentChunkCount);
        tag.putInt("safetyChunkCount", safetyChunkCount);
        tag.putString("safetyChunkDigest", safetyChunkDigest);
        tag.putString("markerFingerprint", markerFingerprint);
        tag.putString("profileSha256", profileSha256);
        tag.putString("assetSha256", assetSha256);
        tag.putString("contractSha256", contractSha256);
        return tag;
    }

    public String validate() {
        if (!MAP_ID.equals(mapId)) return "map ID is '" + mapId + "', expected '" + MAP_ID + "'";
        if (!MISSION_ID.equals(missionId)) return "mission ID is '" + missionId + "', expected '" + MISSION_ID + "'";
        if (mapVersion != MAP_VERSION) return "map version is " + mapVersion + ", expected " + MAP_VERSION;
        if (worldSeed != WORLD_SEED) return "world seed fingerprint does not match Sector 01";
        if (originX != ORIGIN_X || originZ != ORIGIN_Z) return "authored origin is not (0,0)";
        if (contentChunkCount != EXPECTED_CONTENT_CHUNKS) return "authored chunk count is not 308";
        if (safetyChunkCount != EXPECTED_SAFETY_CHUNKS) return "safety chunk count is not 680";
        if (!expectedSafetyChunkDigest().equalsIgnoreCase(safetyChunkDigest)) return "safety chunk inventory digest does not match";
        if (!AshenSpanMapContract.EXPECTED_MARKER_FINGERPRINT.equals(markerFingerprint)) return "marker fingerprint does not match";
        if (!EXPECTED_PROFILE_SHA256.equalsIgnoreCase(profileSha256)) return "profile SHA-256 does not match the locked profile";
        if (!EXPECTED_ASSET_SHA256.equalsIgnoreCase(assetSha256)) return "asset SHA-256 does not match the locked Sector 01 asset";
        if (!EXPECTED_CONTRACT_SHA256.equalsIgnoreCase(contractSha256)) return "contract SHA-256 does not match the locked geometry contract";
        return "";
    }

    static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-fA-F]{64}");
    }

    static String sha256(byte[] value) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public static String expectedSafetyChunkDigest() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (int x = SAFETY_MIN_CHUNK_X; x <= SAFETY_MAX_CHUNK_X; x++) {
                for (int z = SAFETY_MIN_CHUNK_Z; z <= SAFETY_MAX_CHUNK_Z; z++) {
                    digest.update((x + "," + z + "\n").getBytes(StandardCharsets.US_ASCII));
                }
            }
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte b : value) {
            result.append(Character.forDigit((b >>> 4) & 0xf, 16));
            result.append(Character.forDigit(b & 0xf, 16));
        }
        return result.toString();
    }
}
