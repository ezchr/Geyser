/*
 * Copyright (c) 2019-2024 GeyserMC. http://geysermc.org
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

package org.geysermc.geyser.skin;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.protocol.bedrock.data.skin.AnimatedTextureType;
import org.cloudburstmc.protocol.bedrock.data.skin.AnimationData;
import org.cloudburstmc.protocol.bedrock.data.skin.AnimationExpressionType;
import org.cloudburstmc.protocol.bedrock.data.skin.ImageData;
import org.cloudburstmc.protocol.bedrock.data.skin.PersonaPieceData;
import org.cloudburstmc.protocol.bedrock.data.skin.PersonaPieceTintData;
import org.cloudburstmc.protocol.bedrock.data.skin.PersonaPieceType;
import org.cloudburstmc.protocol.bedrock.data.skin.SerializedSkin;
import org.cloudburstmc.protocol.bedrock.packet.PlayerListPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerSkinPacket;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.api.skin.Cape;
import org.geysermc.geyser.api.skin.Skin;
import org.geysermc.geyser.api.skin.SkinData;
import org.geysermc.geyser.api.skin.SkinGeometry;
import org.geysermc.geyser.entity.type.player.AvatarEntity;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.session.auth.BedrockClientData;
import org.geysermc.geyser.text.GeyserLocale;
import org.geysermc.geyser.util.FileUtils;
import org.geysermc.geyser.util.PlayerListUtils;
import org.geysermc.geyser.util.WebUtils;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.auth.texture.Texture;
import org.geysermc.mcprotocollib.auth.texture.TextureModel;
import org.geysermc.mcprotocollib.auth.texture.TextureType;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.ResolvableProfile;

import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class SkinManager {

    private static final Map<ResolvableProfile, CompletableFuture<GameProfile>> requestedProfiles = new ConcurrentHashMap<>();
    private static final Cache<ResolvableProfile, GameProfile> RESOLVED_PROFILES_CACHE = CacheBuilder.newBuilder()
        .expireAfterAccess(1, TimeUnit.HOURS)
        .build();
    private static final UUID EMPTY_UUID = new UUID(0L, 0L);
    static final String GEOMETRY = new String(FileUtils.readAllBytes("bedrock/geometries/geo.json"), StandardCharsets.UTF_8);

    /**
     * Builds a Bedrock player list entry from our existing, cached Bedrock skin information
     */
    public static PlayerListPacket.Entry buildEntryFromCachedSkin(GeyserSession session, AvatarEntity playerEntity) {
        // First: see if we have the cached skin texture ID.
        GameProfileData data = GameProfileData.from(playerEntity);
        Skin skin = null;
        Cape cape = null;
        SkinGeometry geometry = SkinGeometry.WIDE;
        if (data != null) {
            // GameProfileData is not null = server provided us with textures data to work with.
            skin = SkinProvider.getCachedSkin(data.skinUrl());
            cape = SkinProvider.getCachedCape(data.capeUrl());
            geometry = data.isSlim() ? SkinGeometry.SLIM : SkinGeometry.WIDE;
        }

        if (skin == null || cape == null) {
            // The server either didn't have a texture to send, or we didn't have the texture ID cached.
            // Let's see if this player is a Bedrock player, and if so, let's pull their skin.
            // Otherwise, grab the default player skin
            SkinData fallbackSkinData = SkinProvider.determineFallbackSkinData(playerEntity.uuid());
            if (skin == null) {
                skin = fallbackSkinData.skin();
                geometry = fallbackSkinData.geometry();
            }
            if (cape == null) {
                cape = fallbackSkinData.cape();
            }
        }

        return PlayerListUtils.buildEntryManually(
                session,
                playerEntity.uuid(),
                playerEntity.getUsername(),
                playerEntity.geyserId(),
                getSkin(session, playerEntity.uuid(), skin.textureUrl(), skin, cape, geometry)
        );
    }

    public static void sendSkinPacket(GeyserSession session, AvatarEntity entity, SkinData skinData) {
        Skin skin = skinData.skin();
        Cape cape = skinData.cape();
        SkinGeometry geometry = skinData.geometry();

        // Since 1.21.130: PlayerSkinPacket only works if player is listed; might as well always use the player list packet
        if (entity.uuid().equals(session.getPlayerEntity().uuid()) || !entity.isListed()) {
            PlayerListPacket.Entry entry = PlayerListUtils.buildEntryManually(
                session,
                entity.uuid(),
                entity.getUsername(),
                entity.geyserId(),
                getSkin(session, entity.uuid(), skin.textureUrl(), skin, cape, geometry)
            );

            // Slight delay ensures skins are actually shown
            session.scheduleInEventLoop(() -> {
                PlayerListUtils.sendSkinUsingPlayerList(session, entry, entity, entity.isListed());
            }, 100, TimeUnit.MILLISECONDS);
        } else {
            SerializedSkin serializedSkin = getSkin(session, entity.uuid(), skin.textureUrl(), skin, cape, geometry);
            PlayerSkinPacket packet = new PlayerSkinPacket();
            packet.setUuid(entity.uuid());
            packet.setOldSkinName("");
            packet.setNewSkinName(serializedSkin.getSkinId());
            packet.setSkin(serializedSkin);
            packet.setTrustedSkin(true);
            session.sendUpstreamPacket(packet);
        }
    }

    private static SerializedSkin getSkin(GeyserSession session, UUID uuid, String skinId, Skin skin, Cape cape, SkinGeometry geometry) {
        SerializedSkin personaSkin = personaSkin(uuid);
        if (personaSkin != null) {
            return personaSkin;
        }
        return SerializedSkin.builder()
            .skinId(skinId)
            .skinResourcePatch(geometry.geometryName())
            .skinData(ImageData.of(skin.skinData()))
            .capeData(ImageData.of(cape.capeData()))
            .geometryData(geometry.geometryData().isBlank() ? GEOMETRY : geometry.geometryData())
            .premium(true)
            .capeId(cape.capeId())
            .fullSkinId(skinId)
            .geometryDataEngineVersion(session.getClientData().getGameVersion())
            .overridingPlayerAppearance(true)
            .color(new Color(0, true))
            .trusted(true)
            .profileHash("") // TODO Look into this, the session sends it, so it's probably important for skins to work correctly
            .build();
    }

    /**
     * Builds the persona skin a Bedrock player on this Geyser instance logged in with. Its Java texture does not
     * fit the persona geometry, so Bedrock players are sent the original skin instead.
     *
     * @return the skin, or null if the player is not on this instance or has no persona skin
     */
    private static @Nullable SerializedSkin personaSkin(UUID uuid) {
        GeyserSession owner = GeyserImpl.getInstance().connectionByUuid(uuid);
        if (owner == null || !owner.getClientData().isPersonaSkin()) {
            return null;
        }
        BedrockClientData data = owner.getClientData();
        try {
            List<AnimationData> animations = new ArrayList<>();
            if (data.getAnimatedImageData() != null) {
                for (BedrockClientData.AnimatedImage image : data.getAnimatedImageData()) {
                    animations.add(new AnimationData(
                        ImageData.of(image.getImageWidth(), image.getImageHeight(), image.getImage()),
                        AnimatedTextureType.values()[image.getType()],
                        image.getFrames(),
                        AnimationExpressionType.values()[image.getAnimationExpression()]
                    ));
                }
            }

            List<PersonaPieceData> pieces = new ArrayList<>();
            if (data.getPersonaPieces() != null) {
                for (BedrockClientData.PersonaPiece piece : data.getPersonaPieces()) {
                    pieces.add(new PersonaPieceData(piece.getPieceId(), personaPieceType(piece.getPieceType()),
                        UUID.fromString(piece.getPackId()), piece.isDefault(), Objects.requireNonNullElse(piece.getProductId(), "")));
                }
            }

            List<PersonaPieceTintData> tints = new ArrayList<>();
            if (data.getPieceTintColors() != null) {
                for (BedrockClientData.PieceTintColor tint : data.getPieceTintColors()) {
                    // The protocol carries exactly four colours per piece; unused ones are sent as zero.
                    List<Color> colours = new ArrayList<>(4);
                    for (int i = 0; i < 4; i++) {
                        colours.add(argb(tint.getColors() != null && i < tint.getColors().size() ? tint.getColors().get(i) : null));
                    }
                    tints.add(new PersonaPieceTintData(personaPieceType(tint.getPieceType()), colours));
                }
            }

            byte[] engineVersion = data.getGeometryDataEngineVersion();
            byte[] animationData = data.getSkinAnimationData();
            byte[] capeData = data.getCapeData();
            return SerializedSkin.builder()
                .skinId(data.getSkinId())
                .playFabId(Objects.requireNonNullElse(data.getPlayFabId(), ""))
                .skinResourcePatch(new String(data.getGeometryName(), StandardCharsets.UTF_8))
                .skinData(ImageData.of(data.getSkinImageWidth(), data.getSkinImageHeight(), data.getSkinData()))
                .animations(animations)
                .capeData(capeData == null || capeData.length == 0 ? ImageData.EMPTY : ImageData.of(data.getCapeImageWidth(), data.getCapeImageHeight(), capeData))
                .geometryData(new String(data.getGeometryData(), StandardCharsets.UTF_8))
                .geometryDataEngineVersion(engineVersion == null || engineVersion.length == 0 ? data.getGameVersion() : new String(engineVersion, StandardCharsets.UTF_8))
                .animationData(animationData == null ? "" : new String(animationData, StandardCharsets.UTF_8))
                .premium(data.isPremiumSkin())
                .persona(true)
                .capeOnClassic(data.isCapeOnClassicSkin())
                .capeId(Objects.requireNonNullElse(data.getCapeId(), ""))
                .fullSkinId(data.getSkinId())
                .armSize(Objects.requireNonNullElse(data.getArmSize(), "wide"))
                .skinColor(Objects.requireNonNullElse(data.getSkinColor(), "#0"))
                .color(argb(data.getSkinColor()))
                .personaPieces(pieces)
                .tintColors(tints)
                .overridingPlayerAppearance(true)
                .trusted(true)
                .profileHash("")
                .build();
        } catch (Exception e) {
            GeyserImpl.getInstance().getLogger().debug("Could not build the persona skin of " + data.getUsername() + ": " + e);
            return null;
        }
    }

    private static PersonaPieceType personaPieceType(String name) {
        try {
            return PersonaPieceType.fromName(name);
        } catch (IllegalArgumentException e) {
            return PersonaPieceType.UNSUPPORTED;
        }
    }

    /**
     * Parses a persona colour such as "#ffa12722" (ARGB). Anything else is transparent.
     */
    private static Color argb(@Nullable String hex) {
        if (hex == null || !hex.startsWith("#")) {
            return new Color(0, true);
        }
        try {
            return new Color((int) Long.parseLong(hex.substring(1), 16), true);
        } catch (NumberFormatException e) {
            return new Color(0, true);
        }
    }

    public static CompletableFuture<GameProfile> resolveProfile(ResolvableProfile profile) {
        GameProfile partial = profile.getProfile();
        if (!profile.isDynamic()) {
            // This is easy: the server has provided the entire profile for us (or however much it knew),
            // and is asking us to use this
            return CompletableFuture.completedFuture(partial);
        } else if (!partial.getProperties().isEmpty() || (partial.getId() == null && partial.getName() == null)) {
            // If properties have been provided to us, or no ID and no name have been provided, create a static profile from
            // what we do know
            // This replicates vanilla Java client behaviour
            String name = partial.getName() == null ? "" : partial.getName();
            UUID uuid = partial.getName() == null ? EMPTY_UUID : createOfflinePlayerUUID(partial.getName());
            GameProfile completed = new GameProfile(uuid, name);
            completed.setProperties(partial.getProperties());
            return CompletableFuture.completedFuture(completed);
        }

        GameProfile cached = RESOLVED_PROFILES_CACHE.getIfPresent(profile);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        return requestedProfiles.computeIfAbsent(profile, resolvableProfile -> {
            CompletableFuture<GameProfile> future = (partial.getName() != null
                    ? SkinProvider.requestUUIDFromUsername(partial.getName()).thenApply(uuid -> new GameProfile(uuid, partial.getName()))
                    : SkinProvider.requestUsernameFromUUID(partial.getId()).thenApply(name -> new GameProfile(partial.getId(), name))
                ).thenCompose(nameAndUUID -> {
                        if (nameAndUUID.getId() == null || nameAndUUID.getName() == null) {
                            return CompletableFuture.completedFuture(partial);
                        }

                        return SkinProvider.requestTexturesFromUUID(nameAndUUID.getId())
                            .thenApply(encoded -> {
                                if (encoded == null) return partial;
                                nameAndUUID.setProperties(List.of(new GameProfile.Property("textures", encoded)));
                                return nameAndUUID;
                            });
                    })
                    .thenApply(resolved -> {
                        RESOLVED_PROFILES_CACHE.put(resolvableProfile, resolved);
                        return resolved;
                    });
            return future.whenComplete((r, t) -> requestedProfiles.remove(resolvableProfile));
        });
    }

    public static @Nullable Texture getTextureDataFromProfile(GameProfile profile, TextureType type) {
        Map<TextureType, Texture> textures;
        try {
            textures = profile.getTextures(false);
        } catch (IllegalStateException e) {
            GeyserImpl.getInstance().getLogger().debug("Could not decode textures from game profile (%s)! Got: %s", profile, e);
            return null;
        }

        if (textures == null) {
            return null;
        }
        return textures.get(type);
    }

    public static void requestAndHandleSkinAndCape(AvatarEntity entity, GeyserSession session, Consumer<SkinProvider.SkinAndCape> skinAndCapeConsumer) {
        SkinProvider.requestSkinData(entity, session).whenCompleteAsync((skinData, throwable) -> {
            if (skinData != null && skinData.geometry() != null) {
                sendSkinPacket(session, entity, skinData);
            }

            if (skinAndCapeConsumer != null) {
                skinAndCapeConsumer.accept(skinData == null ? null : new SkinProvider.SkinAndCape(skinData.skin(), skinData.cape()));
            }
        });
    }

    public static void handleBedrockSkin(AvatarEntity playerEntity, BedrockClientData clientData) {
        GeyserImpl geyser = GeyserImpl.getInstance();
        if (geyser.config().debugMode()) {
            geyser.getLogger().info(GeyserLocale.getLocaleStringLog("geyser.skin.bedrock.register", playerEntity.getUsername(), playerEntity.uuid()));
        }

        try {
            byte[] skinBytes = clientData.getSkinData();
            byte[] capeBytes = clientData.getCapeData();

            byte[] geometryNameBytes = clientData.getGeometryName();
            byte[] geometryBytes = clientData.getGeometryData();

            if (skinBytes.length <= (128 * 128 * 4) && !clientData.isPersonaSkin()) {
                SkinProvider.storeBedrockSkin(playerEntity.uuid(), clientData.getSkinId(), skinBytes);
                SkinProvider.storeBedrockGeometry(playerEntity.uuid(), geometryNameBytes, geometryBytes);
            } else if (geyser.config().debugMode()) {
                geyser.getLogger().info(GeyserLocale.getLocaleStringLog("geyser.skin.bedrock.fail", playerEntity.getUsername()));
                geyser.getLogger().debug("The size of '" + playerEntity.getUsername() + "' skin is: " + clientData.getSkinImageWidth() + "x" + clientData.getSkinImageHeight());
            }

            if (!clientData.getCapeId().isEmpty()) {
                SkinProvider.storeBedrockCape(clientData.getCapeId(), capeBytes);
            }
        } catch (Exception e) {
            throw new AssertionError("Failed to cache skin for bedrock user (" + playerEntity.getUsername() + "): ", e);
        }
    }

    public static UUID createOfflinePlayerUUID(String username) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
    }

    public record GameProfileData(String skinUrl, String capeUrl, boolean isSlim) {
        /**
         * Generate the GameProfileData from the given player entity
         *
         * @param entity entity to build the GameProfileData from
         * @return The built GameProfileData
         */
        public static @Nullable GameProfileData from(AvatarEntity entity) {
            Map<TextureType, Texture> textures = entity.getTextures();
            if (textures == null) {
                // Likely offline mode or failed to load
                // We'll fall back to default skins
                return null;
            }

            Texture skin = textures.get(TextureType.SKIN);
            if (skin == null) {
                return null;
            }

            String skinUrl = WebUtils.toHttps(skin.getURL());
            if (Objects.equals(DEFAULT_FLOODGATE_STEVE, skinUrl)) {
                // https://github.com/GeyserMC/Floodgate/commit/00b8b1b6364116ff4bc9b00e2015ce35bae8abb1 ensures that
                // Bedrock players on online-mode servers will always have a textures property. However, this skin is
                // also sent our way, and isn't overwritten. It's very likely that this skin is *only* a placeholder,
                // and no one should ever be using it outside of Floodgate, and therefore no one wants to see this
                // specific Steve skin.
                return null;
            }

            Texture cape = textures.get(TextureType.CAPE);
            String capeUrl = cape == null ? null : WebUtils.toHttps(cape.getURL());
            return new GameProfileData(skinUrl, capeUrl, skin.getModel() == TextureModel.SLIM);
        }

        private static final String DEFAULT_FLOODGATE_STEVE = "https://textures.minecraft.net/texture/31f477eb1a7beee631c2ca64d06f8f68fa93a3386d04452ab27f43acdf1b60cb";
    }
}
