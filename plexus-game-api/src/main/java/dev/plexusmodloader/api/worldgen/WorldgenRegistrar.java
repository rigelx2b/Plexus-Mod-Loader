package dev.plexusmodloader.api.worldgen;

import dev.plexusmodloader.api.resource.ResourceId;
import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;

/**
 * Changes world generation. The features themselves are data: put
 * {@code data/<mod id>/worldgen/configured_feature/*.json} and {@code placed_feature/*.json} in the
 * mod jar, in the game's format. Custom dimensions are data too ({@code dimension/},
 * {@code dimension_type/}). This registrar attaches placed features to existing biomes, which data
 * alone cannot do without replacing the biome files.
 *
 * <p><b>When:</b> common entrypoints. Applied every time a server loads its worlds; affects chunks
 * generated afterwards.
 */
@ApiStability(StabilityLevel.PREVIEW)
public interface WorldgenRegistrar {

    /**
     * Adds a placed feature at the end of {@code step} in every selected biome.
     *
     * @param placedFeature ID of a placed feature from any data pack, for example the mod's own
     */
    void addFeature(BiomeSelector biomes, GenerationStepName step, ResourceId placedFeature);

    /**
     * Adds a natural spawn of any entity type to every selected biome (since 2.1.0): a vanilla mob, a mob of another mod,
     * or the mod's own. The game's own spawns of the biome stay. The natural spawns of an {@code EntityDefinition} do the
     * same for a mod's own entity type; this is for entity types the mod does not define.
     *
     * <p>The entity type is looked up when a server loads its worlds. A type that is not registered then (for example a
     * mod that is not installed) is logged with {@code PLEXUS-WORLDGEN-006} and skipped, so optional mobs do not stop the
     * server. Fabric API: {@code BiomeModifications.addSpawn}; NeoForge: biome modifiers.
     *
     * @param spawn the entity type, spawn group, weight, and group size
     */
    void addSpawn(BiomeSelector biomes, BiomeSpawn spawn);

    /**
     * Takes the natural spawn of an entity type out of every selected biome (since 2.1.0), whoever put it there: the
     * game, a data pack, or another mod. It removes the type from every spawn category of the biome. Removals are applied
     * before additions, so a mod replaces a spawn by calling this and then {@link #addSpawn} with its own numbers.
     *
     * <p><b>When:</b> common entrypoints; applied each time a server loads its worlds. Affects spawning from then on;
     * mobs that already exist stay. An entity type that is not registered then is logged with
     * {@code PLEXUS-WORLDGEN-007} and skipped. A type the biome does not spawn is not an error. Fabric API:
     * {@code BiomeModifications} with {@code removeSpawn}.
     *
     * @param entityType ID of the entity type, for example {@code minecraft:sheep}
     * @throws IllegalStateException when called after registration ended ({@code PLEXUS-WORLDGEN-002})
     */
    void removeSpawn(BiomeSelector biomes, ResourceId entityType);

    /**
     * Takes a placed feature out of {@code step} in every selected biome (since 2.1.0), for example the trees or ores of
     * the game. Removals are applied before additions, so a mod replaces a feature by removing it and calling
     * {@link #addFeature} with its own.
     *
     * <p><b>When:</b> common entrypoints; applied each time a server loads its worlds, so it affects chunks generated
     * afterwards (chunks already generated keep what they have). A placed feature that does not exist is logged with
     * {@code PLEXUS-WORLDGEN-007} and skipped; a feature the biome does not have in that step is not an error.
     *
     * @param placedFeature ID of a placed feature, for example {@code minecraft:trees_plains}
     * @throws IllegalStateException when called after registration ended ({@code PLEXUS-WORLDGEN-002})
     */
    void removeFeature(BiomeSelector biomes, GenerationStepName step, ResourceId placedFeature);

    /**
     * Registers terrain written in code as generator type {@code <mod id>:<path>}, for dimensions in
     * data packs.
     *
     * @return the generator type ID
     * @throws IllegalArgumentException for an invalid or used path ({@code PLEXUS-WORLDGEN-001})
     */
    ResourceId chunkGenerator(String path, TerrainGenerator generator);

    /**
     * Defines biome {@code <mod id>:<path>} in code. Use it in dimensions and biome tags like a biome
     * from a data pack.
     *
     * @return the biome ID
     */
    ResourceId biome(String path, BiomeDefinition definition);

    /**
     * Registers a feature written in code as feature type {@code <mod id>:<path>}, and writes a
     * configured and a placed feature with the same ID into Plexus Mod Loader's generated data pack. Add it to biomes
     * with {@link #addFeature}, place it with {@code /place feature <id>}, or use the type in the
     * mod's own data pack.
     *
     * @return the feature ID
     * @throws IllegalArgumentException for an invalid or used path ({@code PLEXUS-WORLDGEN-004})
     */
    ResourceId feature(String path, FeaturePlacement placement, CodeFeature feature);

    /**
     * Registers a density function written in code as type {@code <mod id>:<path>}. Data packs use it
     * inside the game's noise terrain, for example in a noise settings' {@code final_density}: {@code
     * {"type": "minecraft:add", "argument1": …, "argument2": {"type": "<mod id>:<path>"}}}.
     *
     * @param minValue the lowest value {@code function} returns; the game uses the bounds to skip work
     * @param maxValue the highest value {@code function} returns
     * @return the density function type ID
     * @throws IllegalArgumentException for an invalid or used path ({@code PLEXUS-WORLDGEN-004}) or {@code
     *     minValue > maxValue}
     */
    ResourceId densityFunction(String path, double minValue, double maxValue, NoiseFunction function);

    /**
     * Registers a structure built in code as {@code <mod id>:<path>}, with its structure set, in Plexus Mod Loader's
     * generated data pack. It generates in new chunks of the selected biomes; {@code /locate structure}
     * and {@code /place structure} find and place it.
     *
     * @return the structure ID
     * @throws IllegalArgumentException for an invalid or used path ({@code PLEXUS-WORLDGEN-004})
     */
    ResourceId structure(String path, StructureDefinition definition);
}
