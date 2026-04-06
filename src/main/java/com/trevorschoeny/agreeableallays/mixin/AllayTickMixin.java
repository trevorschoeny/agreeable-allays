package com.trevorschoeny.agreeableallays.mixin;

import com.trevorschoeny.agreeableallays.AgreeableAllaysMemory;
import com.trevorschoeny.agreeableallays.access.SittingAllay;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ticks the detach timer on every server AI step.
 *
 * When a player takes an allay's held item, AllayInteractionMixin sets
 * DETACH_TICKS_REMAINING to 200 (10 seconds). Each server tick we
 * decrement by 1. When it reaches zero:
 *   - LIKED_PLAYER is erased (allay unbonds)
 *   - Sitting state is cleared (allay can wander again)
 *   - The timer memory is erased
 *
 * If the player gives the allay a new item before the timer expires,
 * AllayInteractionMixin clears the timer and vanilla re-bonds the allay.
 */
@Mixin(Allay.class)
public abstract class AllayTickMixin {

    /**
     * Checks all nearby players for direct gaze (15-degree cone). When a player
     * is looking directly at the allay, sets INTERESTED_IN_PLAYER and flies the
     * allay to a position 1.5 blocks in front of the player's eyes. This overlay
     * overrides all state-specific positioning behaviors.
     */
    @Inject(method = "customServerAiStep", at = @At("HEAD"))
    private void agreeableallays$tickInterested(ServerLevel serverLevel, CallbackInfo ci) {
        Allay allay = (Allay) (Object) this;
        Brain<Allay> brain = allay.getBrain();

        ServerPlayer lookingPlayer = null;
        boolean alreadyInterested = brain.hasMemoryValue(AgreeableAllaysMemory.INTERESTED_IN_PLAYER);

        for (ServerPlayer player : serverLevel.players()) {
            double distToAllay = player.distanceTo(allay);
            if (distToAllay > 64.0) continue;

            Vec3 viewVec = player.getViewVector(1.0f);
            Vec3 toAllay = allay.position().subtract(player.getEyePosition());
            double dist = toAllay.length();
            if (dist < 0.1) { lookingPlayer = player; break; }
            toAllay = toAllay.normalize();

            // Wider cone when close so it's easier to look at a nearby allay
            double coneDeg = distToAllay < 2.0 ? 30.0 : distToAllay < 6.0 ? 20.0 : 15.0;
            boolean lookingAtAllay = viewVec.dot(toAllay) > Math.cos(Math.toRadians(coneDeg));
            if (!lookingAtAllay) continue;

            // Shift + look initiates Interest. Once interested, gaze alone sustains it.
            if (alreadyInterested && player.getUUID().equals(
                    brain.getMemory(AgreeableAllaysMemory.INTERESTED_IN_PLAYER).orElse(null))) {
                // Already interested in this player — sustain as long as they're looking
                lookingPlayer = player;
                break;
            } else if (player.isShiftKeyDown()) {
                // New interest — requires shift to initiate
                lookingPlayer = player;
                break;
            }
        }

        if (lookingPlayer != null) {
            brain.setMemory(AgreeableAllaysMemory.INTERESTED_IN_PLAYER, lookingPlayer.getUUID());

            // Fly toward the player — target is in front of their eyes, slightly below cursor
            Vec3 playerLook = lookingPlayer.getViewVector(1.0f);
            Vec3 eyePos = lookingPlayer.getEyePosition();
            Vec3 targetPos = new Vec3(
                    eyePos.x + playerLook.x * 2.0,
                    eyePos.y + playerLook.y * 2.0 - 0.25,
                    eyePos.z + playerLook.z * 2.0
            );

            double distToTarget = allay.position().distanceTo(targetPos);

            if (distToTarget > 6.0) {
                // Far: pathfind at full speed
                brain.setMemory(MemoryModuleType.WALK_TARGET,
                        new WalkTarget(targetPos, 2.25f, 2));
            } else {
                // Close: lerp for smooth deceleration
                double lerpFactor = Math.min(0.15, 0.5 / Math.max(distToTarget, 0.1));
                Vec3 currentPos = allay.position();
                Vec3 newPos = new Vec3(
                        currentPos.x + (targetPos.x - currentPos.x) * lerpFactor,
                        currentPos.y + (targetPos.y - currentPos.y) * lerpFactor,
                        currentPos.z + (targetPos.z - currentPos.z) * lerpFactor
                );
                allay.setPos(newPos);
                allay.setDeltaMovement(Vec3.ZERO);
                allay.getNavigation().stop();
                brain.eraseMemory(MemoryModuleType.WALK_TARGET);
            }

            // Force look at player every tick — set brain target, look control,
            // AND directly set head/body rotation for reliable facing
            brain.setMemory(MemoryModuleType.LOOK_TARGET,
                    new EntityTracker(lookingPlayer, true));
            allay.getLookControl().setLookAt(
                    lookingPlayer.getX(), lookingPlayer.getEyeY(), lookingPlayer.getZ(),
                    360.0f, 360.0f);
            // Direct rotation — guarantees facing even when look control is overridden
            double dx = lookingPlayer.getX() - allay.getX();
            double dy = lookingPlayer.getEyeY() - allay.getEyeY();
            double dz = lookingPlayer.getZ() - allay.getZ();
            double horizDist = Math.sqrt(dx * dx + dz * dz);
            float yaw = (float)(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            float pitch = (float)(-Math.toDegrees(Math.atan2(dy, horizDist)));
            allay.setYRot(yaw);
            allay.setYHeadRot(yaw);
            allay.setXRot(pitch);
        } else {
            // No player looking — clear interested state
            brain.eraseMemory(AgreeableAllaysMemory.INTERESTED_IN_PLAYER);
            // Don't clear WALK_TARGET — the state-specific behavior will set it
        }
    }

    @Inject(method = "customServerAiStep", at = @At("TAIL"))
    private void agreeableallays$tickDetachTimer(ServerLevel serverLevel, CallbackInfo ci) {
        Allay allay = (Allay) (Object) this;
        Brain<Allay> brain = allay.getBrain();

        brain.getMemory(AgreeableAllaysMemory.DETACH_TICKS_REMAINING).ifPresent(ticks -> {
            if (ticks <= 1) {
                // Timer expired: unbond from owner and clear sitting state
                brain.eraseMemory(MemoryModuleType.LIKED_PLAYER);
                brain.eraseMemory(AgreeableAllaysMemory.DETACH_TICKS_REMAINING);
                ((SittingAllay) allay).agreeableallays$setSitting(false);
            } else {
                // Decrement the countdown
                brain.setMemory(AgreeableAllaysMemory.DETACH_TICKS_REMAINING, ticks - 1);
            }
        });
    }
}
