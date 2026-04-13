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
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
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

        // Sitting allays cannot be interested — clear if somehow set
        if (((SittingAllay) allay).agreeableallays$isSitting()) {
            brain.eraseMemory(AgreeableAllaysMemory.INTERESTED_IN_PLAYER);
            return;
        }

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

            // Line-of-sight check — can't interest an allay through a solid block.
            // Uses VISUAL shape so sight passes through glass, ice, and other see-through
            // blocks (their visual shape is empty) but still blocks through solid walls.
            BlockHitResult hitResult = serverLevel.clip(new ClipContext(
                    player.getEyePosition(), allay.position(),
                    ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player));
            if (hitResult.getType() == HitResult.Type.BLOCK) continue;

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

            // If the target point is inside a solid block (e.g. player looking through
            // a glass pane), don't move the allay — it would clip into the block.
            net.minecraft.core.BlockPos targetBlock = net.minecraft.core.BlockPos.containing(targetPos);
            boolean targetInSolid = !serverLevel.getBlockState(targetBlock)
                    .getCollisionShape(serverLevel, targetBlock).isEmpty();

            double distToTarget = allay.position().distanceTo(targetPos);

            if (targetInSolid) {
                // Target is inside a block — hold position, don't move
                allay.getNavigation().stop();
                brain.eraseMemory(MemoryModuleType.WALK_TARGET);
            } else if (distToTarget > 6.0) {
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

                // Don't lerp through blocks — raycast from current position to the
                // lerp target and clamp to just before the hit point if obstructed.
                BlockHitResult moveHit = serverLevel.clip(new ClipContext(
                        currentPos, newPos,
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, allay));
                if (moveHit.getType() == HitResult.Type.BLOCK) {
                    // Stop just before the block face (0.1 back along the direction)
                    Vec3 hitPos = moveHit.getLocation();
                    Vec3 dir = newPos.subtract(currentPos).normalize();
                    newPos = hitPos.subtract(dir.scale(0.1));
                }

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

    /**
     * After all brain behaviors run, enforce sitting state by killing any
     * movement that vanilla behaviors or RandomStroll may have queued.
     * A sitting allay stays exactly where it is — no wandering, no item pickup movement.
     */
    @Inject(method = "customServerAiStep", at = @At("TAIL"))
    private void agreeableallays$enforceSitting(ServerLevel serverLevel, CallbackInfo ci) {
        Allay allay = (Allay) (Object) this;
        if (((SittingAllay) allay).agreeableallays$isSitting()) {
            Brain<Allay> brain = allay.getBrain();
            brain.eraseMemory(MemoryModuleType.WALK_TARGET);
            brain.eraseMemory(MemoryModuleType.NEAREST_VISIBLE_WANTED_ITEM);
            allay.getNavigation().stop();
            allay.setDeltaMovement(allay.getDeltaMovement().multiply(0.0, 1.0, 0.0));

            // Sitting allays occasionally dance — once every ~25 seconds on average.
            // Check every second (20 ticks), ~4% chance = roughly every 25 seconds.
            if (allay.tickCount % 20 == 0) {
                if (!allay.isDancing() && allay.getRandom().nextFloat() < 0.04f) {
                    allay.setDancing(true);
                } else if (allay.isDancing() && allay.getRandom().nextFloat() < 0.5f) {
                    // ~50% chance per second to stop — dances last ~2 seconds
                    allay.setDancing(false);
                }
            }
        }
    }

    /**
     * Companion allays occasionally dance while following the player.
     * ~5% chance per second (checked every 20 ticks) to start a 3-second dance.
     * Dancing stops automatically when the allay needs to move.
     */
    @Inject(method = "customServerAiStep", at = @At("TAIL"))
    private void agreeableallays$companionIdleDance(ServerLevel serverLevel, CallbackInfo ci) {
        Allay allay = (Allay) (Object) this;
        if (((SittingAllay) allay).agreeableallays$isSitting()) return;
        if (!allay.getBrain().hasMemoryValue(MemoryModuleType.LIKED_PLAYER)) return;
        if (allay.tickCount % 20 != 0) return;

        if (!allay.isDancing() && allay.getRandom().nextFloat() < 0.05f) {
            // Start a short dance — vanilla handles the animation duration
            allay.setDancing(true);
        } else if (allay.isDancing() && allay.getRandom().nextFloat() < 0.33f) {
            // ~33% chance per second to stop dancing so it doesn't go forever
            allay.setDancing(false);
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
