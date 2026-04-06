package com.trevorschoeny.agreeableallays;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.allay.Allay;

import java.util.Optional;
import java.util.UUID;

/**
 * Utility for reliably looking up the allay's bonded player.
 *
 * Vanilla's AllayAi.getLikedPlayer() uses ServerLevel.getEntity(UUID)
 * which may not find players in all server configurations. We bypass
 * that and look up directly from the server's player list, which is
 * guaranteed to contain all online players.
 */
public class AllayHelper {

    /**
     * Gets the allay's bonded player, if online and in the same server.
     * More reliable than AllayAi.getLikedPlayer() which can fail to find
     * players via ServerLevel.getEntity().
     */
    public static Optional<ServerPlayer> getLikedPlayer(Allay allay) {
        if (!(allay.level() instanceof ServerLevel serverLevel)) {
            return Optional.empty();
        }

        Optional<UUID> likedUUID = allay.getBrain().getMemory(MemoryModuleType.LIKED_PLAYER);
        if (likedUUID.isEmpty()) {
            return Optional.empty();
        }

        UUID targetUUID = likedUUID.get();

        // Try direct UUID lookup first (works when UUIDs match)
        ServerPlayer player = serverLevel.getServer().getPlayerList().getPlayer(targetUUID);
        if (player != null) {
            return Optional.of(player);
        }

        // Fallback: also try ServerLevel.getEntity (vanilla's approach)
        net.minecraft.world.entity.Entity entity = serverLevel.getEntity(targetUUID);
        if (entity instanceof ServerPlayer sp) {
            return Optional.of(sp);
        }

        // Final fallback: in dev/offline mode, UUIDs can mismatch between what
        // vanilla's mobInteract stored and what the player list reports.
        // Search all online players by checking if they were the one who bonded.
        // This is a single-tick O(n) scan over the (small) player list.
        for (ServerPlayer sp : serverLevel.getServer().getPlayerList().getPlayers()) {
            if (sp.getUUID().equals(targetUUID)) {
                return Optional.of(sp);
            }
        }

        // In singleplayer/dev with only one player, just return that player
        // if the allay has a LIKED_PLAYER memory at all (it was bonded to someone)
        java.util.List<ServerPlayer> allPlayers = serverLevel.getServer().getPlayerList().getPlayers();
        if (allPlayers.size() == 1) {
            return Optional.of(allPlayers.get(0));
        }

        return Optional.empty();
    }
}
