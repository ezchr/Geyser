package org.geysermc.geyser.zid;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.WeakHashMap;
import org.cloudburstmc.math.vector.Vector3f;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.session.GeyserSession;

/**
 * ZID: records every time the Java server moves a Bedrock player (setbacks, knockback corrections,
 * plugin teleports) to zid_snapbacks.log in the Geyser folder, to find what causes rubber-banding.
 * One line per event: time, player, distance, from, to, ms since the last knockback, flags.
 */
public final class ZidSnapLog {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final Map<GeyserSession, Long> LAST_MOTION = new WeakHashMap<>();

    private ZidSnapLog() {
    }

    /** The server sent this player a velocity (knockback, explosion, plugin push). */
    public static synchronized void motion(GeyserSession session) {
        LAST_MOTION.put(session, System.currentTimeMillis());
    }

    /** The server moved this player from where Geyser last had them to a new spot. */
    public static void teleport(GeyserSession session, Vector3f from, Vector3f to, String flags) {
        long since;
        synchronized (ZidSnapLog.class) {
            Long m = LAST_MOTION.get(session);
            since = m == null ? -1 : System.currentTimeMillis() - m;
        }
        String line = String.format("%s\t%s\t%.3f\t%.2f %.2f %.2f\t%.2f %.2f %.2f\t%d\t%s%n",
                LocalDateTime.now().format(TIME), session.bedrockUsername(), from.distance(to),
                from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ(), since, flags);
        try {
            Path f = GeyserImpl.getInstance().getBootstrap().getConfigFolder().resolve("zid_snapbacks.log");
            Files.writeString(f, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }
}
