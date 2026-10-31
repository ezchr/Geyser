package org.geysermc.geyser.zid;

import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.session.auth.BedrockClientData;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Saves the persona skins players log in with (image, geometry, resource patch, pieces) while the
 * folder persona_dumps exists in the Geyser config folder. A development aid for the persona to Java
 * skin conversion; delete the folder to turn it off.
 */
public final class ZidPersonaDump {
    private ZidPersonaDump() {
    }

    public static void dump(String username, BedrockClientData data) {
        if (!data.isPersonaSkin()) {
            return;
        }
        Path root = GeyserImpl.getInstance().getBootstrap().getConfigFolder().resolve("persona_dumps");
        if (!Files.isDirectory(root)) {
            return;
        }
        Thread.ofVirtual().start(() -> {
            try {
                Path dir = root.resolve(username.replaceAll("[^A-Za-z0-9_.-]", "_") + "_" + Integer.toHexString(data.getSkinId().hashCode()));
                if (Files.isDirectory(dir)) {
                    return;
                }
                Files.createDirectories(dir);
                int w = data.getSkinImageWidth(), h = data.getSkinImageHeight();
                byte[] px = data.getSkinData();
                BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                for (int i = 0; i < w * h && i * 4 + 3 < px.length; i++) {
                    int r = px[i * 4] & 0xff, g = px[i * 4 + 1] & 0xff, b = px[i * 4 + 2] & 0xff, a = px[i * 4 + 3] & 0xff;
                    img.setRGB(i % w, i / w, a << 24 | r << 16 | g << 8 | b);
                }
                ImageIO.write(img, "png", dir.resolve("skin.png").toFile());
                Files.write(dir.resolve("geometry.json"), data.getGeometryData());
                Files.write(dir.resolve("resource_patch.json"), data.getGeometryName());
                StringBuilder info = new StringBuilder();
                info.append("skin ").append(w).append('x').append(h).append(" bytes ").append(px.length).append('\n');
                info.append("skinId ").append(data.getSkinId()).append('\n');
                info.append("armSize ").append(data.getArmSize()).append(" skinColor ").append(data.getSkinColor()).append('\n');
                info.append("cape ").append(data.getCapeId()).append(' ').append(data.getCapeImageWidth()).append('x').append(data.getCapeImageHeight()).append('\n');
                if (data.getAnimatedImageData() != null) {
                    for (BedrockClientData.AnimatedImage a : data.getAnimatedImageData()) {
                        info.append("animated type ").append(a.getType()).append(' ').append(a.getImageWidth()).append('x').append(a.getImageHeight()).append(" frames ").append(a.getFrames()).append('\n');
                    }
                }
                if (data.getPersonaPieces() != null) {
                    for (BedrockClientData.PersonaPiece p : data.getPersonaPieces()) {
                        info.append("piece ").append(p.getPieceType()).append(' ').append(p.getPieceId()).append(" default=").append(p.isDefault()).append('\n');
                    }
                }
                Files.writeString(dir.resolve("info.txt"), info.toString(), StandardCharsets.UTF_8);
                GeyserImpl.getInstance().getLogger().info("[zid] saved the persona skin of " + username + " to " + dir);
            } catch (Exception e) {
                GeyserImpl.getInstance().getLogger().warning("[zid] could not save the persona skin of " + username + ": " + e);
            }
        });
    }
}
