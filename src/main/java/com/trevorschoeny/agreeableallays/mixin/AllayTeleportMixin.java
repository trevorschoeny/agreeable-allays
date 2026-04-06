package com.trevorschoeny.agreeableallays.mixin;

import com.trevorschoeny.agreeableallays.AgreeableAllaysMemory;
import com.trevorschoeny.agreeableallays.access.SittingAllay;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.animal.allay.AllayAi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Teleports bonded allays to their owner when they get too far away.
 *
 * Injects at TAIL of customServerAiStep (same hook point as AllayTickMixin's
 * detach timer, but in a separate file for clean separation of concerns).
 *
 * Design rationale — tick injection over Brain Behavior:
 *   - Teleportation should happen regardless of which brain activity is running
 *   - It's a simple distance check + position set, not a navigation behavior
 *   - A cooldown (every 20 ticks = 1 second) avoids per-tick overhead
 *
 * Conditions to skip teleport:
 *   - Client side (never)
 *   - No LIKED_PLAYER memory (unbonded)
 *   - Sitting (sitting allays stay put forever)
 *   - In detach period (has DETACH_TICKS_REMAINING — allay is about to unbond)
 *   - Player offline or in different dimension (getLikedPlayer returns empty)
 *   - Within 64 blocks (not far enough to warrant teleport)
 */
@Mixin(Allay.class)
public abstract class AllayTeleportMixin {

    // Cooldown counter — only check teleport every 20 ticks (1 second)
    @Unique
    private int agreeableallays$teleportCooldown = 0;

    // 64 blocks squared — matches vanilla wolf-like teleport threshold
    @Unique
    private static final double TELEPORT_THRESHOLD_SQ = 64.0 * 64.0;

    @Inject(method = "customServerAiStep", at = @At("TAIL"))
    private void agreeableallays$tickTeleportCheck(ServerLevel serverLevel, CallbackInfo ci) {
        // Only check once per second to avoid per-tick overhead
        if (++agreeableallays$teleportCooldown < 20) return;
        agreeableallays$teleportCooldown = 0;

        Allay allay = (Allay) (Object) this;

        // Skip if sitting — sitting allays stay put
        if (((SittingAllay) allay).agreeableallays$isSitting()) return;

        // Skip if in detach period — allay is lingering, about to unbond
        if (allay.getBrain().getMemory(AgreeableAllaysMemory.DETACH_TICKS_REMAINING).isPresent()) return;

        // Get the liked player. After AllayAiDistanceMixin removes the 64-block
        // cap, this returns the player as long as they're in the same dimension
        // and online. Returns empty if player is offline or different dimension.
        ServerPlayer player = com.trevorschoeny.agreeableallays.AllayHelper.getLikedPlayer(allay).orElse(null);
        if (player == null) return;

        double distSq = allay.distanceToSqr(player);
        if (distSq <= TELEPORT_THRESHOLD_SQ) return;

        // Teleport! Adapted from TamableAnimal.teleportToAroundBlockPos().
        // 10 random attempts around the player, offset ±3 X/Z, ±1 Y.
        // Allays can fly, so we only check the target isn't inside a solid block
        // (no ground check needed like wolves).
        for (int i = 0; i < 10; i++) {
            int offsetX = agreeableallays$randomOffset(allay, 3);
            int offsetZ = agreeableallays$randomOffset(allay, 3);
            int offsetY = allay.getRandom().nextIntBetweenInclusive(-1, 1);

            // Avoid teleporting directly on top of the player
            if (Math.abs(offsetX) < 2 && Math.abs(offsetZ) < 2) continue;

            BlockPos target = player.blockPosition().offset(offsetX, offsetY, offsetZ);

            // Safety: don't teleport into a solid block
            if (allay.level().getBlockState(target).isSolid()) continue;

            // Teleport to center of block
            allay.teleportTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
            allay.getNavigation().stop(); // Clear any stale pathfinding
            break;
        }
    }

    /**
     * Returns a random offset in [-range, range] using the allay's RNG.
     */
    @Unique
    private static int agreeableallays$randomOffset(Allay allay, int range) {
        return allay.getRandom().nextIntBetweenInclusive(-range, range);
    }
}
