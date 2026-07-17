package grcmcs.minecraft.mods.pomkotsmechs.arena;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/** One durable Ashen Span block mutation with its complete original block state. */
public final class MissionGateBlock {
    private final ResourceLocation dimension;
    private final BlockPos pos;
    private final ResourceLocation block;
    private final Map<String, String> properties;

    public MissionGateBlock(ResourceLocation dimension, BlockPos pos, BlockState state) {
        this(dimension, pos, BuiltInRegistries.BLOCK.getKey(state.getBlock()),
                encodeProperties(state));
    }

    private MissionGateBlock(ResourceLocation dimension, BlockPos pos,
                             ResourceLocation block, Map<String, String> properties) {
        this.dimension = dimension;
        this.pos = pos.immutable();
        this.block = block;
        this.properties = Map.copyOf(properties);
    }

    public ResourceLocation dimension() {
        return dimension;
    }

    public ResourceKey<Level> dimensionKey() {
        return ResourceKey.create(Registries.DIMENSION, dimension);
    }

    public BlockPos pos() {
        return pos;
    }

    public ResourceLocation block() {
        return block;
    }

    public Map<String, String> properties() {
        return properties;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("dim", dimension.toString());
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
        tag.putString("block", block.toString());
        CompoundTag state = new CompoundTag();
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            state.putString(entry.getKey(), entry.getValue());
        }
        tag.put("properties", state);
        return tag;
    }

    @Nullable
    public static MissionGateBlock load(CompoundTag tag) {
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("dim"));
        ResourceLocation block = ResourceLocation.tryParse(tag.getString("block"));
        if (dimension == null || block == null) {
            return null;
        }
        CompoundTag state = tag.getCompound("properties");
        Map<String, String> properties = new LinkedHashMap<>();
        for (String key : state.getAllKeys()) {
            properties.put(key, state.getString(key));
        }
        return new MissionGateBlock(dimension,
                new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z")),
                block, properties);
    }

    static MissionGateBlock encodedForTests(ResourceLocation dimension, BlockPos pos,
                                            ResourceLocation block,
                                            Map<String, String> properties) {
        return new MissionGateBlock(dimension, pos, block, properties);
    }

    /** Returns null when a persisted property no longer exists or cannot be decoded. */
    @Nullable
    public BlockState decodeState() {
        Block resolved = BuiltInRegistries.BLOCK.get(block);
        if (resolved == null || !BuiltInRegistries.BLOCK.containsKey(block)) {
            return null;
        }
        BlockState state = resolved.defaultBlockState();
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            Property<?> property = resolved.getStateDefinition().getProperty(entry.getKey());
            if (property == null) {
                return null;
            }
            state = apply(state, property, entry.getValue());
            if (state == null) {
                return null;
            }
        }
        return state;
    }

    @Nullable
    private static <T extends Comparable<T>> BlockState apply(
            BlockState state, Property<T> property, String encoded) {
        return property.getValue(encoded).map(value -> state.setValue(property, value)).orElse(null);
    }

    private static Map<String, String> encodeProperties(BlockState state) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<Property<?>, Comparable<?>> entry : state.getValues().entrySet()) {
            result.put(entry.getKey().getName(), propertyName(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String propertyName(Property property, Comparable value) {
        return property.getName(value);
    }
}
