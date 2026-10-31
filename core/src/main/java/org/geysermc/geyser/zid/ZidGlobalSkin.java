package org.geysermc.geyser.zid;

import com.google.gson.JsonObject;
import org.geysermc.floodgate.pluginmessage.PluginMessageChannels;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.util.PluginMessageUtils;
import org.geysermc.geyser.util.WebUtils;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Gives Floodgate a Bedrock player's real skin when Geyser's own skin uploader is off (it only runs with
 * validate-bedrock-login, which the relay setup turns off). The relay uploads each player's login to
 * GeyserMC's global API, so the signed Java texture is looked up there by XUID and sent on the Floodgate
 * skin channel, as the uploader does with the API's answer. Without it Java players see Floodgate's
 * placeholder Steve for every Bedrock player.
 */
public final class ZidGlobalSkin {
    private static final String LOOKUP_URL = "https://api.geysermc.org/v2/skin/";
    // The relay uploads the login around the time the player joins, so look again a little later.
    private static final long[] ATTEMPT_DELAYS_SECONDS = {2, 15, 45};

    private ZidGlobalSkin() {
    }

    public static void apply(GeyserSession session) {
        String xuid = session.xuid();
        if (xuid == null || xuid.isEmpty()) {
            return;
        }
        schedule(session, xuid, 0, null);
    }

    private static void schedule(GeyserSession session, String xuid, int attempt, String lastSignature) {
        if (attempt >= ATTEMPT_DELAYS_SECONDS.length) {
            return;
        }
        GeyserImpl geyser = GeyserImpl.getInstance();
        geyser.getScheduledThread().schedule(() -> geyser.getScheduledThread().execute(() -> {
            if (session.isClosed()) {
                return;
            }
            String sent = lastSignature;
            try {
                JsonObject skin = WebUtils.getJson(LOOKUP_URL + xuid);
                String value = skin.has("value") ? skin.get("value").getAsString() : "";
                String signature = skin.has("signature") ? skin.get("signature").getAsString() : "";
                if (!value.isEmpty() && !signature.isEmpty() && !signature.equals(lastSignature)) {
                    send(session, (value + '\0' + signature).getBytes(StandardCharsets.UTF_8), 0);
                    sent = signature;
                }
            } catch (Exception e) {
                geyser.getLogger().debug("[zid] global skin lookup failed for " + session.bedrockUsername() + ": " + e);
            }
            schedule(session, xuid, attempt + 1, sent);
        }), ATTEMPT_DELAYS_SECONDS[attempt], TimeUnit.SECONDS);
    }

    private static void send(GeyserSession session, byte[] bytes, int attempt) {
        if (session.isClosed() || attempt >= 10) {
            return;
        }
        if (session.isSpawned()) {
            PluginMessageUtils.sendMessage(session, PluginMessageChannels.SKIN, bytes);
            return;
        }
        GeyserImpl.getInstance().getScheduledThread().schedule(() -> send(session, bytes, attempt + 1), 500, TimeUnit.MILLISECONDS);
    }
}
