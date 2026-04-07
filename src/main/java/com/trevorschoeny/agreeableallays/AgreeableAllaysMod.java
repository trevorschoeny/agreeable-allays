package com.trevorschoeny.agreeableallays;

import com.trevorschoeny.agreeableallays.access.SittingAllay;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.allay.Allay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AgreeableAllaysMod implements ModInitializer {
    public static final String MOD_ID = "agreeable-allays";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        // Register custom memory types before any allay brain is built
        AgreeableAllaysMemory.register();

        // Rescue bonded allays from chunk unloads — teleport them to their owner
        // so they don't get stranded when the player moves too fast for the allay
        // to keep up (elytra, boats, etc.)
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            if (!(entity instanceof Allay allay)) return;

            // Sitting allays intentionally stay put — don't rescue them
            if (((SittingAllay) allay).agreeableallays$isSitting()) return;

            // Detaching allays are about to unbond — let them despawn naturally
            if (allay.getBrain().getMemory(AgreeableAllaysMemory.DETACH_TICKS_REMAINING).isPresent()) return;

            // Look up the bonded player — returns empty if unbonded, offline, or different dimension
            ServerPlayer player = AllayHelper.getLikedPlayer(allay).orElse(null);
            if (player == null) return;

            // Only rescue if the player is in the same dimension
            if (player.level() != world) return;

            // Defer the teleport to the next tick — teleportTo() during ENTITY_UNLOAD
            // causes "Entity is already tracked!" because the entity is still registered
            // in the old chunk's tracker when it gets added to the new one.
            ((ServerLevel) world).getServer().execute(() -> {
                // Re-check the allay is still alive after the tick delay
                if (allay.isRemoved()) return;

                // Teleport to the player — same random-offset logic as AllayTeleportMixin
                // 10 attempts, ±3 X/Z, ±1 Y, skip solid blocks
                for (int i = 0; i < 10; i++) {
                    int offsetX = allay.getRandom().nextIntBetweenInclusive(-3, 3);
                    int offsetZ = allay.getRandom().nextIntBetweenInclusive(-3, 3);
                    int offsetY = allay.getRandom().nextIntBetweenInclusive(-1, 1);

                    // Avoid teleporting directly on top of the player
                    if (Math.abs(offsetX) < 2 && Math.abs(offsetZ) < 2) continue;

                    BlockPos target = player.blockPosition().offset(offsetX, offsetY, offsetZ);

                    // Safety: don't teleport into a solid block
                    if (world.getBlockState(target).isSolid()) continue;

                    // Teleport to center of block
                    allay.teleportTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
                    allay.getNavigation().stop();
                    break;
                }
            });
        });

        LOGGER.info("[AgreeableAllays] Loaded successfully!");
    }
}
