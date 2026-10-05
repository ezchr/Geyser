/*
 * Copyright (c) 2019-2022 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.geyser.translator.protocol.java.level;

import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.BlockParticleData;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.ColorParticleData;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.DustParticleData;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.ItemParticleData;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.Particle;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.TrailParticleData;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.VibrationParticleData;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.positionsource.BlockPositionSource;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.positionsource.EntityPositionSource;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelParticlesPacket;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.LevelEvent;
import org.cloudburstmc.protocol.bedrock.data.ParticleType;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelEventGenericPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.SpawnParticleEffectPacket;
import org.geysermc.geyser.entity.type.Entity;
import org.geysermc.geyser.registry.Registries;
import org.geysermc.geyser.registry.type.ParticleMapping;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.translator.item.ItemTranslator;
import org.geysermc.geyser.translator.protocol.PacketTranslator;
import org.geysermc.geyser.translator.protocol.Translator;
import org.geysermc.geyser.util.DimensionUtils;

import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

@Translator(packet = ClientboundLevelParticlesPacket.class)
public class JavaLevelParticlesTranslator extends PacketTranslator<ClientboundLevelParticlesPacket> {
    private static final int MAX_PARTICLES = 100;

    // ZID: per player, one particle type at one block spawns at most every SPOT_GAP_MS (bursts
    // every BURST_GAP_MS). Command blocks and trails send particles every tick, and Bedrock's live
    // several times longer than Java's, so the same stream piled up into a dense cloud.
    private static final long SPOT_GAP_MS = 250;
    private static final long BURST_GAP_MS = 500;
    private static final java.util.Map<GeyserSession, java.util.Map<Long, Long>> LAST_SPAWN =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    // ZID: Bedrock particle limits come from zid_particles.json in the Geyser folder, re-read within
    // ~2 s of a change (no restart). "default" applies to every particle, "burst" to emitter/level-event
    // ones, "particles" overrides single types (Java particle names, e.g. TRIAL_SPAWNER_DETECTED_PLAYER).
    // Fields: gap_ms (minimum time between puffs from one spot), cell (spot size in blocks),
    // count_percent (share of the Java count kept, at least 1), max_count (cap per puff).
    record Limit(long gapMs, double cell, int countPercent, int maxCount) {
        Limit with(com.google.gson.JsonObject o) {
            if (o == null) return this;
            return new Limit(o.has("gap_ms") ? o.get("gap_ms").getAsLong() : gapMs,
                    o.has("cell") ? Math.max(0.25, o.get("cell").getAsDouble()) : cell,
                    o.has("count_percent") ? o.get("count_percent").getAsInt() : countPercent,
                    o.has("max_count") ? o.get("max_count").getAsInt() : maxCount);
        }

        int amount(int javaAmount) {
            int n = Math.max(1, (int) Math.ceil(javaAmount * countPercent / 100.0));
            return maxCount > 0 ? Math.min(n, maxCount) : n;
        }
    }

    private static final String DEFAULT_LIMITS = """
            {
              "_help": "Bedrock particle limits, applied within 2 seconds of saving (no restart). default = every particle, burst = emitter/level-event ones, particles = single Java particle types. gap_ms = minimum time between puffs from one spot, cell = spot size in blocks, count_percent = share of the Java count kept (at least 1), max_count = cap per puff (0 = none).",
              "default": {"gap_ms": 250, "cell": 1, "count_percent": 25, "max_count": 0},
              "burst": {"gap_ms": 500, "cell": 1, "count_percent": 100, "max_count": 1},
              "particles": {
                "TRIAL_SPAWNER_DETECTED_PLAYER": {"gap_ms": 600, "cell": 3, "max_count": 1},
                "TRIAL_SPAWNER_DETECTED_PLAYER_OMINOUS": {"gap_ms": 600, "cell": 3, "max_count": 1},
                "TRIAL_OMEN": {"gap_ms": 600, "cell": 3, "max_count": 1}
              }
            }
            """;
    private static volatile Limit DEFAULT_LIMIT = new Limit(250, 1, 25, 0);
    private static volatile Limit BURST_LIMIT = new Limit(500, 1, 100, 1);
    private static volatile java.util.Map<String, Limit> TYPE_LIMITS = java.util.Map.of();
    private static volatile long limitsCheckedAt;
    private static volatile long limitsModified = -1;

    private static synchronized void reloadLimitsIfChanged() {
        long now = System.currentTimeMillis();
        if (now - limitsCheckedAt < 2000) return;
        limitsCheckedAt = now;
        try {
            java.nio.file.Path f = org.geysermc.geyser.GeyserImpl.getInstance().getBootstrap().getConfigFolder().resolve("zid_particles.json");
            if (!java.nio.file.Files.exists(f)) java.nio.file.Files.writeString(f, DEFAULT_LIMITS);
            long mod = java.nio.file.Files.getLastModifiedTime(f).toMillis();
            if (mod == limitsModified) return;
            limitsModified = mod;
            com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(f)).getAsJsonObject();
            Limit def = new Limit(250, 1, 25, 0).with(o.getAsJsonObject("default"));
            Limit burst = new Limit(500, 1, 100, 1).with(o.getAsJsonObject("burst"));
            java.util.Map<String, Limit> types = new java.util.HashMap<>();
            com.google.gson.JsonObject parts = o.getAsJsonObject("particles");
            if (parts != null) {
                for (var e : parts.entrySet()) {
                    types.put(e.getKey().toUpperCase(java.util.Locale.ROOT), (e.getValue().isJsonObject() ? def : def).with(e.getValue().getAsJsonObject()));
                }
            }
            DEFAULT_LIMIT = def;
            BURST_LIMIT = burst;
            TYPE_LIMITS = types;
            org.geysermc.geyser.GeyserImpl.getInstance().getLogger().info("Particle limits loaded from zid_particles.json (" + types.size() + " particle overrides)");
        } catch (Exception e) {
            org.geysermc.geyser.GeyserImpl.getInstance().getLogger().warning("zid_particles.json not applied: " + e.getMessage());
        }
    }

    /** The limit for this particle: its own entry, else burst or default. */
    private static Limit limit(Particle particle) {
        reloadLimitsIfChanged();
        Limit own = TYPE_LIMITS.get(particle.getType().name());
        if (own != null) return own;
        return isBurst(particle) ? BURST_LIMIT : DEFAULT_LIMIT;
    }

    /** True if this particle may spawn here now for this player (and records it). */
    private static boolean spotFree(GeyserSession session, Particle particle, double x, double y, double z, boolean burst) {
        Limit lim = limit(particle);
        double cell = lim.cell();
        long key = ((long) particle.getType().ordinal() << 48)
                ^ ((long) Math.floor(x / cell) * 73856093L) ^ ((long) Math.floor(y / cell) * 19349663L) ^ ((long) Math.floor(z / cell) * 83492791L);
        long now = System.currentTimeMillis();
        java.util.Map<Long, Long> seen = LAST_SPAWN.computeIfAbsent(session, k -> new java.util.HashMap<>());
        synchronized (seen) {
            if (seen.size() > 4096) seen.clear();
            Long last = seen.get(key);
            if (last != null && now - last < lim.gapMs()) return false;
            seen.put(key, now);
            return true;
        }
    }

    @Override
    public void translate(GeyserSession session, ClientboundLevelParticlesPacket packet) {
        Function<Vector3f, BedrockPacket> particleCreateFunction = createParticle(session, packet.getParticle());
        if (particleCreateFunction != null) {
            if (!spotFree(session, packet.getParticle(), packet.getX(), packet.getY(), packet.getZ(), isBurst(packet.getParticle()))) {
                return;
            }
            if (packet.getAmount() == 0) {
                // 0 means don't apply the offset
                Vector3f position = Vector3f.from(packet.getX(), packet.getY(), packet.getZ());
                session.sendUpstreamPacket(particleCreateFunction.apply(position));
            } else {
                Random random = ThreadLocalRandom.current();
                // ZID: Bedrock particles are bigger and longer-lived than Java's, and emitter /
                // level-event ones are whole bursts: the same count looked many times denser on
                // Bedrock. Bursts are sent once, the rest at a quarter of the count.
                int amount = Math.min(MAX_PARTICLES, packet.getAmount());
                amount = limit(packet.getParticle()).amount(amount);
                for (int i = 0; i < amount; i++) {
                    double offsetX = random.nextGaussian() * (double) packet.getOffsetX();
                    double offsetY = random.nextGaussian() * (double) packet.getOffsetY();
                    double offsetZ = random.nextGaussian() * (double) packet.getOffsetZ();
                    Vector3f position = Vector3f.from(packet.getX() + offsetX, packet.getY() + offsetY, packet.getZ() + offsetZ);

                    session.sendUpstreamPacket(particleCreateFunction.apply(position));
                }
            }
        } else {
            // Null is only returned when no particle of this type is found
            session.getGeyser().getLogger().debug("Unhandled particle packet: " + packet);
        }
    }

    /**
     * @param session the Bedrock client session.
     * @param particle the Java particle to translate to a Bedrock equivalent.
     * @return a function to create a packet with a specified particle, in the event we need to spawn multiple particles
     * with different offsets.
     */
    public static @Nullable Function<Vector3f, BedrockPacket> createParticle(GeyserSession session, Particle particle) {
        switch (particle.getType()) {
            case BLOCK -> {
                int blockState = session.getBlockMappings().getBedrockBlockId(((BlockParticleData) particle.getData()).getBlockState());
                return (position) -> {
                    LevelEventPacket packet = new LevelEventPacket();
                    packet.setType(LevelEvent.PARTICLE_CRACK_BLOCK);
                    packet.setPosition(position);
                    packet.setData(blockState);
                    return packet;
                };
            }
            case FALLING_DUST -> {
                int blockState = session.getBlockMappings().getBedrockBlockId(((BlockParticleData) particle.getData()).getBlockState());
                return (position) -> {
                    LevelEventPacket packet = new LevelEventPacket();
                    // In fact, FallingDustParticle should have data like DustParticle,
                    // but in MCProtocol, its data is BlockState(1).
                    packet.setType(ParticleType.FALLING_DUST);
                    packet.setData(blockState);
                    packet.setPosition(position);
                    return packet;
                };
            }
            case ITEM -> {
                ItemStack javaItem = ((ItemParticleData) particle.getData()).getItemStack();
                ItemData bedrockItem = ItemTranslator.translateToBedrock(session, javaItem);
                int data = bedrockItem.getDefinition().getRuntimeId() << 16 | bedrockItem.getDamage();
                return (position) -> {
                    LevelEventPacket packet = new LevelEventPacket();
                    packet.setType(ParticleType.ICON_CRACK);
                    packet.setData(data);
                    packet.setPosition(position);
                    return packet;
                };
            }
            case DUST, DUST_COLOR_TRANSITION -> { //TODO
                DustParticleData data = (DustParticleData) particle.getData();
                int rgbData = data.getColor();
                return (position) -> {
                    LevelEventPacket packet = new LevelEventPacket();
                    packet.setType(ParticleType.FALLING_DUST);
                    packet.setData(rgbData);
                    packet.setPosition(position);
                    return packet;
                };
            }
            case VIBRATION -> {
                VibrationParticleData data = (VibrationParticleData) particle.getData();

                Vector3f target;
                if (data.getPositionSource() instanceof BlockPositionSource blockPositionSource) {
                    target = blockPositionSource.getPosition().toFloat().add(0.5f, 0.5f, 0.5f);
                } else if (data.getPositionSource() instanceof EntityPositionSource entityPositionSource) {
                    Entity entity = session.getEntityCache().getEntityByJavaId(entityPositionSource.getEntityId());
                    if (entity != null) {
                        target = entity.bedrockPosition().up(entityPositionSource.getYOffset());
                    } else {
                        session.getGeyser().getLogger().debug("Unable to find entity with Java Id: " + entityPositionSource.getEntityId() + " for vibration particle.");
                        return null;
                    }
                } else {
                    session.getGeyser().getLogger().debug("Unknown position source " + data.getPositionSource() + " for vibration particle.");
                    return null;
                }

                return (position) -> {
                    LevelEventGenericPacket packet = new LevelEventGenericPacket();
                    packet.setType(LevelEvent.PARTICLE_VIBRATION_SIGNAL);
                    packet.setTag(
                            NbtMap.builder()
                                    .putCompound("origin", buildVec3PositionTag(position))
                                    .putCompound("target", buildVec3PositionTag(target)) // There is a way to target an entity but that takes an attachPos instead of a y offset
                                    .putFloat("speed", 20f)
                                    .putFloat("timeToLive", data.getArrivalTicks() / 20f)
                                    .build()
                    );
                    return packet;
                };
            }
            case FIREWORK -> {
                int dimensionId = DimensionUtils.javaToBedrock(session);
                return (position) -> {
                    SpawnParticleEffectPacket particlePacket = new SpawnParticleEffectPacket();
                    particlePacket.setIdentifier("minecraft:sparkler_emitter");
                    particlePacket.setDimensionId(dimensionId);
                    particlePacket.setPosition(position);
                    particlePacket.setMolangVariablesJson(Optional.of("[{ \"name\": \"variable.color\", \"value\": { \"type\": \"member_array\", \"value\": [{\"name\": \".r\", \"value\": { \"type\": \"float\", \"value\": 1.0}},{\"name\": \".g\", \"value\": {\"type\": \"float\", \"value\": 1.0}},{\"name\": \".b\", \"value\": {\"type\": \"float\", \"value\": 1.0}},{\"name\": \".a\", \"value\": {\"type\": \"float\", \"value\": 1.0}}]}}]"));
                    return particlePacket;
                };
            }
            case TINTED_LEAVES -> {
                int dimensionId = DimensionUtils.javaToBedrock(session);
                ColorParticleData data = (ColorParticleData) particle.getData();
                int rgbData = data.getColor();
                float red = ((rgbData >> 16) & 0xFF) / 255f;
                float green = ((rgbData >> 8) & 0xFF) / 255f;
                float blue = (rgbData & 0xFF) / 255f;
                return (position) -> {
                    SpawnParticleEffectPacket particlePacket = new SpawnParticleEffectPacket();
                    particlePacket.setIdentifier("minecraft:biome_tinted_leaves_particle");
                    particlePacket.setDimensionId(dimensionId);
                    particlePacket.setPosition(position);
                    particlePacket.setMolangVariablesJson(Optional.of(colorMolang(red, green, blue)));
                    return particlePacket;
                };
            }
            case GLOW -> {
                int dimensionId = DimensionUtils.javaToBedrock(session);
                return (position) -> {
                    SpawnParticleEffectPacket particlePacket = new SpawnParticleEffectPacket();
                    particlePacket.setIdentifier("minecraft:glow_particle");
                    particlePacket.setDimensionId(dimensionId);
                    particlePacket.setPosition(position);
                    // The Java client randomly picks a light or dark cyan for each particle
                    particlePacket.setMolangVariablesJson(Optional.of(ThreadLocalRandom.current().nextBoolean()
                            ? colorMolang(0.6f, 1.0f, 0.8f)
                            : colorMolang(0.08f, 0.4f, 0.4f)));
                    return particlePacket;
                };
            }
            case WAX_ON -> {
                int dimensionId = DimensionUtils.javaToBedrock(session);
                return (position) -> {
                    SpawnParticleEffectPacket particlePacket = new SpawnParticleEffectPacket();
                    particlePacket.setIdentifier("minecraft:wax_particle");
                    particlePacket.setDimensionId(dimensionId);
                    particlePacket.setPosition(position);
                    particlePacket.setMolangVariablesJson(Optional.of(colorMolang(0.91f, 0.55f, 0.08f)));
                    return particlePacket;
                };
            }
            case WAX_OFF -> {
                int dimensionId = DimensionUtils.javaToBedrock(session);
                return (position) -> {
                    SpawnParticleEffectPacket particlePacket = new SpawnParticleEffectPacket();
                    particlePacket.setIdentifier("minecraft:wax_particle");
                    particlePacket.setDimensionId(dimensionId);
                    particlePacket.setPosition(position);
                    particlePacket.setMolangVariablesJson(Optional.of(colorMolang(1.0f, 0.9f, 1.0f)));
                    return particlePacket;
                };
            }
            case SCRAPE -> {
                int dimensionId = DimensionUtils.javaToBedrock(session);
                return (position) -> {
                    SpawnParticleEffectPacket particlePacket = new SpawnParticleEffectPacket();
                    particlePacket.setIdentifier("minecraft:wax_particle");
                    particlePacket.setDimensionId(dimensionId);
                    particlePacket.setPosition(position);
                    // The Java client randomly picks a dark or light teal for each particle
                    particlePacket.setMolangVariablesJson(Optional.of(ThreadLocalRandom.current().nextBoolean()
                            ? colorMolang(0.29f, 0.58f, 0.51f)
                            : colorMolang(0.43f, 0.77f, 0.62f)));
                    return particlePacket;
                };
            }
            case TRAIL -> {
                TrailParticleData data = (TrailParticleData) particle.getData();
                int dimensionId = DimensionUtils.javaToBedrock(session);
                Vector3f target = data.target().toFloat();
                int rgbData = data.color();
                float red = ((rgbData >> 16) & 0xFF) / 255f;
                float green = ((rgbData >> 8) & 0xFF) / 255f;
                float blue = (rgbData & 0xFF) / 255f;
                float lifetime = Math.max(data.duration(), 1) / 20f;
                return (position) -> {
                    Vector3f direction = target.sub(position);
                    float distance = direction.length();
                    Vector3f normalized = distance > 0 ? direction.div(distance) : Vector3f.ZERO;
                    SpawnParticleEffectPacket particlePacket = new SpawnParticleEffectPacket();
                    particlePacket.setIdentifier("minecraft:creaking_heart_trail");
                    particlePacket.setDimensionId(dimensionId);
                    particlePacket.setPosition(position);
                    particlePacket.setMolangVariablesJson(Optional.of("[{ \"name\": \"variable.direction\", \"value\": { \"type\": \"member_array\", \"value\": [{\"name\": \".x\", \"value\": { \"type\": \"float\", \"value\": " + normalized.getX() + "}},{\"name\": \".y\", \"value\": {\"type\": \"float\", \"value\": " + normalized.getY() + "}},{\"name\": \".z\", \"value\": {\"type\": \"float\", \"value\": " + normalized.getZ() + "}}]}},{ \"name\": \"variable.color\", \"value\": { \"type\": \"member_array\", \"value\": [{\"name\": \".r\", \"value\": { \"type\": \"float\", \"value\": " + red + "}},{\"name\": \".g\", \"value\": {\"type\": \"float\", \"value\": " + green + "}},{\"name\": \".b\", \"value\": {\"type\": \"float\", \"value\": " + blue + "}}]}},{ \"name\": \"variable.max_lifetime\", \"value\": { \"type\": \"float\", \"value\": " + lifetime + "}},{ \"name\": \"variable.particle_initial_speed\", \"value\": { \"type\": \"float\", \"value\": " + (distance / lifetime) + "}}]"));
                    return particlePacket;
                };
            }
            default -> {
                ParticleMapping particleMapping = Registries.PARTICLES.get(particle.getType());
                if (particleMapping == null) { //TODO ensure no particle can be null
                    return null;
                }

                if (particleMapping.levelEventType() != null) {
                    return (position) -> {
                        LevelEventPacket packet = new LevelEventPacket();
                        packet.setType(particleMapping.levelEventType());
                        packet.setPosition(position);
                        return packet;
                    };
                } else if (particleMapping.identifier() != null) {
                    int dimensionId = DimensionUtils.javaToBedrock(session);
                    return (position) -> {
                        SpawnParticleEffectPacket stringPacket = new SpawnParticleEffectPacket();
                        stringPacket.setIdentifier(particleMapping.identifier());
                        stringPacket.setDimensionId(dimensionId);
                        stringPacket.setPosition(position);
                        stringPacket.setMolangVariablesJson(Optional.empty());
                        return stringPacket;
                    };
                } else {
                    return null;
                }
            }
        }
    }

    /** A Java particle Geyser shows as a Bedrock emitter or level event: one packet is a burst. */
    private static boolean isBurst(Particle particle) {
        ParticleMapping mapping = Registries.PARTICLES.get(particle.getType());
        if (mapping == null) return false;
        if (mapping.levelEventType() != null) return true;
        return mapping.identifier() != null && mapping.identifier().contains("emitter");
    }

    private static String colorMolang(float red, float green, float blue) {
        return "[{ \"name\": \"variable.color\", \"value\": { \"type\": \"member_array\", \"value\": [{\"name\": \".r\", \"value\": { \"type\": \"float\", \"value\": " + red + "}},{\"name\": \".g\", \"value\": {\"type\": \"float\", \"value\": " + green + "}},{\"name\": \".b\", \"value\": {\"type\": \"float\", \"value\": " + blue + "}}]}}]";
    }

    private static NbtMap buildVec3PositionTag(Vector3f position) {
        return NbtMap.builder()
                .putString("type", "vec3")
                .putFloat("x", position.getX())
                .putFloat("y", position.getY())
                .putFloat("z", position.getZ())
                .build();
    }
}
