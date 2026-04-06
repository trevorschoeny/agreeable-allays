package com.trevorschoeny.agreeableallays.behavior;

import com.trevorschoeny.agreeableallays.AgreeableAllaysMemory;
import com.trevorschoeny.agreeableallays.access.SittingAllay;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.animal.allay.Allay;
import com.trevorschoeny.agreeableallays.AllayHelper;
import net.minecraft.world.phys.Vec3;

import com.trevorschoeny.agreeableallays.AgreeableAllaysMod;

import java.util.Map;
import java.util.Optional;

/**
 * Companion state behavior. Keeps the allay positioned to the side and
 * slightly forward of the player, visible in peripheral vision.
 *
 * Key design rules:
 * - WALK_TARGET is ALWAYS set — no gaps, no sinking
 * - Yields when Interested is active (INTERESTED_IN_PLAYER present)
 * - Yields when item to fetch (NEAREST_VISIBLE_WANTED_ITEM present)
 * - When player looks toward allay (45° cone but not 15°), redirect to opposite side
 *   with 1-second delay before Interested can trigger
 * - Never crosses player's forward field of view
 */
public class StayByOwnerBehavior extends Behavior<Allay> {

    // Companion speed scales by distance: lazy drift when close, sprint to catch up when far
    private static final float SPEED_CLOSE = 1.0f;   // Leisurely when within orbit distance
    private static final float SPEED_FAR = 2.5f;     // Fast catch-up when falling behind

    // Stop navigating within 4 blocks of the target point — very loose, natural roaming
    private static final int CLOSE_ENOUGH_DIST = 4;

    // Roaming sphere: allay picks random positions within this range around the player
    private static final double ROAM_DIST_MIN = 4.0;
    private static final double ROAM_DIST_MAX = 16.0;
    private static final double ROAM_Y_RANGE = 5.0;  // ±5 blocks from player Y

    // Forward avoidance cone: the allay avoids this cone in front of the player.
    // cos(70°) ≈ 0.34 — positions within 70° of forward are pushed out.
    private static final double AVOID_CONE_COS = Math.cos(Math.toRadians(70.0));

    // Minimum distance from the player — allays won't roam closer than this
    private static final double AVOID_SPHERE_RADIUS = 2.0;

    // How often to recalculate position (ticks) — prevents jittery movement
    private static final int RECALC_INTERVAL = 5;

    // Look-toward detection: 45° cone (broad awareness) vs 15° cone (direct gaze)
    private static final double LOOK_TOWARD_COS = Math.cos(Math.toRadians(45.0));
    private static final double LOOK_DIRECT_COS = Math.cos(Math.toRadians(15.0));

    // How long the redirect lasts (ticks) — 1 second
    private static final int REDIRECT_DURATION = 20;

    // Tracks when we last set a new walk target
    private long lastRecalcTick = 0;

    // Countdown for redirect evasion when player turns toward allay
    private int redirectTimer = 0;

    // Cached target position so we always have one to fall back on
    private Vec3 lastTargetPos = null;

    // How many ticks the allay has been in the avoidance cone — used for gradual acceleration
    private int ticksInCone = 0;

    public StayByOwnerBehavior() {
        // Preconditions: must have a liked player, must not be panicking,
        // must not have a wanted item to fetch, must not be in Interested overlay
        // Duration: run indefinitely while conditions hold
        super(
                Map.of(
                        MemoryModuleType.LIKED_PLAYER, MemoryStatus.VALUE_PRESENT,
                        MemoryModuleType.IS_PANICKING, MemoryStatus.VALUE_ABSENT,
                        MemoryModuleType.NEAREST_VISIBLE_WANTED_ITEM, MemoryStatus.VALUE_ABSENT,
                        AgreeableAllaysMemory.INTERESTED_IN_PLAYER, MemoryStatus.VALUE_ABSENT
                ),
                1,
                Integer.MAX_VALUE
        );
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, Allay allay) {
        boolean sitting = ((SittingAllay) allay).agreeableallays$isSitting();

        // Get player directly from memory UUID instead of AllayHelper.getLikedPlayer()
        // which has its own distance/game-mode checks that may interfere
        Optional<java.util.UUID> likedUUID = allay.getBrain().getMemory(MemoryModuleType.LIKED_PLAYER);
        boolean hasLikedMemory = likedUUID.isPresent();
        boolean hasPlayer = AllayHelper.getLikedPlayer(allay).isPresent();

        // Debug
        if (allay.tickCount % 100 == 0 && hasLikedMemory && level != null) {
            // Log the actual player UUIDs on the server for comparison
            Object playerUUIDs = level.getServer().getPlayerList().getPlayers()
                .stream().map(p -> p.getStringUUID()).collect(java.util.stream.Collectors.toList());
            AgreeableAllaysMod.LOGGER.info("[AgreeableAllays] StayByOwner checkStart: sitting={}, likedUUID={}, getLikedPlayer={}, serverPlayers={}",
                sitting, likedUUID.orElse(null), hasPlayer, playerUUIDs);
        }

        if (sitting) return false;
        return hasPlayer;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, Allay allay, long gameTime) {
        if (((SittingAllay) allay).agreeableallays$isSitting()) return false;
        if (allay.getBrain().hasMemoryValue(MemoryModuleType.NEAREST_VISIBLE_WANTED_ITEM)) return false;
        if (allay.getBrain().hasMemoryValue(AgreeableAllaysMemory.INTERESTED_IN_PLAYER)) return false;
        return AllayHelper.getLikedPlayer(allay).isPresent();
    }

    @Override
    protected void start(ServerLevel level, Allay allay, long gameTime) {
        this.lastRecalcTick = 0;
        this.redirectTimer = 0;
        this.lastTargetPos = allay.position();
        AgreeableAllaysMod.LOGGER.info("[AgreeableAllays] StayByOwner STARTED for allay {}", allay.getId());
    }

    @Override
    protected void tick(ServerLevel level, Allay allay, long gameTime) {
        Optional<ServerPlayer> optPlayer = AllayHelper.getLikedPlayer(allay);
        if (optPlayer.isEmpty()) return;
        ServerPlayer player = optPlayer.get();

        // Always look at the player
        allay.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(player, true));

        // If the allay is in the player's forward cone, give it an immediate
        // target behind the player so it flies out of the way instead of freezing.
        Vec3 viewVec = player.getViewVector(1.0f);
        Vec3 toAllay = allay.position().subtract(player.getEyePosition());
        double distToAllay = toAllay.length();
        if (distToAllay > 0.5) {
            double dot = viewVec.dot(toAllay.normalize());
            if (dot > AVOID_CONE_COS) {
                ticksInCone++;
                // Gradual acceleration: starts at 1.0, ramps to full speed over ~5 seconds (100 ticks)
                float t = Math.min(1.0f, ticksInCone / 100.0f);
                float dodgeSpeed = SPEED_CLOSE + (SPEED_FAR - SPEED_CLOSE) * t * t; // quadratic ramp from 1.0 to 2.5

                double behindYaw = Math.toRadians(player.getYRot() + 180.0);
                double behindDist = Math.max(ROAM_DIST_MIN, distToAllay);
                Vec3 behindPos = new Vec3(
                    player.getX() - Math.sin(behindYaw) * behindDist,
                    allay.getY(),
                    player.getZ() + Math.cos(behindYaw) * behindDist
                );
                lastTargetPos = behindPos;
                allay.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(behindPos, dodgeSpeed, CLOSE_ENOUGH_DIST));
                return;
            }
        }

        // Not in cone — reset acceleration
        ticksInCone = 0;

        // Sphere avoidance: if the allay is too close, push it outward
        if (distToAllay < AVOID_SPHERE_RADIUS && distToAllay > 0.1) {
            Vec3 awayDir = toAllay.normalize();
            Vec3 pushPos = new Vec3(
                player.getX() + awayDir.x * ROAM_DIST_MIN,
                allay.getY(),
                player.getZ() + awayDir.z * ROAM_DIST_MIN
            );
            lastTargetPos = pushPos;
            allay.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(pushPos, SPEED_FAR, CLOSE_ENOUGH_DIST));
            return;
        }

        // Check if player is looking TOWARD us (45°) but not DIRECTLY at us (15°).
        // The 15° direct gaze is handled by the Interested overlay in AllayTickMixin.
        // The 45° zone is where we proactively dodge to the opposite side.
        boolean lookingToward = isWithinCone(player, allay, LOOK_TOWARD_COS);
        boolean lookingDirect = isWithinCone(player, allay, LOOK_DIRECT_COS);

        if (lookingToward && !lookingDirect) {
            // Player is turning toward us — redirect to opposite side
            if (redirectTimer == 0) {
                redirectTimer = REDIRECT_DURATION;
            }
            redirectTimer--;
            lastTargetPos = calculateRedirectPosition(allay, player, gameTime);
        } else {
            redirectTimer = 0;

            // Normal companion positioning — recalculate periodically
            if (gameTime - lastRecalcTick >= RECALC_INTERVAL) {
                lastRecalcTick = gameTime;
                lastTargetPos = calculateCompanionPosition(allay, player, gameTime);
            }
        }

        // Scale speed by distance to player — lazy drift when close, sprint when falling behind
        double distToPlayer = allay.distanceTo(player);
        float speed;
        if (distToPlayer > ROAM_DIST_MAX) {
            speed = SPEED_FAR;    // Beyond max roam — catch up fast
        } else if (distToPlayer > ROAM_DIST_MIN) {
            float t = (float)((distToPlayer - ROAM_DIST_MIN) / (ROAM_DIST_MAX - ROAM_DIST_MIN));
            speed = SPEED_CLOSE + (SPEED_FAR - SPEED_CLOSE) * t;
        } else {
            speed = SPEED_CLOSE;  // Close — gentle drift
        }

        // ALWAYS set WALK_TARGET — never leave a gap (allay sinks without it)
        allay.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(lastTargetPos, speed, CLOSE_ENOUGH_DIST));
    }

    @Override
    protected void stop(ServerLevel level, Allay allay, long gameTime) {
        // Do NOT clear WALK_TARGET — other behaviors take over smoothly
    }

    /**
     * Checks if the allay is within a cone of the player's gaze.
     * Uses dot product between the player's view vector and the direction
     * from the player's eyes to the allay.
     */
    private boolean isWithinCone(ServerPlayer player, Allay allay, double cosCutoff) {
        Vec3 viewVec = player.getViewVector(1.0f);
        Vec3 toAllay = allay.position().subtract(player.getEyePosition());
        double dist = toAllay.length();
        if (dist < 0.1) return true;
        return viewVec.dot(toAllay.normalize()) > cosCutoff;
    }

    /**
     * Picks a random roaming position in a sphere around the player,
     * then validates it's not in the forward avoidance cone.
     */
    private Vec3 calculateCompanionPosition(Allay allay, ServerPlayer player, long tick) {
        // Use layered sine waves seeded by entity ID for smooth, unique-per-allay movement
        int id = allay.getId();
        double angleH = tick * 0.015 + id * 1.7;   // horizontal angle (slow rotation)
        double angleV = tick * 0.011 + id * 2.3;    // vertical variation
        double distWave = tick * 0.008 + id * 0.9;  // distance oscillation

        // Horizontal angle: full 360°, we'll validate against the cone after
        double yawOffset = Math.sin(angleH) * 180.0 + Math.cos(angleH * 0.7 + id) * 90.0;

        // Distance: oscillates between min and max
        double distProgress = (Math.sin(distWave) + 1.0) / 2.0;
        double dist = ROAM_DIST_MIN + distProgress * (ROAM_DIST_MAX - ROAM_DIST_MIN);

        // Y offset: oscillates within ±ROAM_Y_RANGE
        double yOffset = Math.sin(angleV) * ROAM_Y_RANGE;

        Vec3 candidate = positionFromAngles(player, yawOffset, dist, yOffset);

        // Check if candidate is in the avoidance cone — if so, push it out
        return pushOutOfCone(player, candidate);
    }

    /**
     * When the player turns toward the allay, pick a position on the
     * opposite side of the player.
     */
    private Vec3 calculateRedirectPosition(Allay allay, ServerPlayer player, long tick) {
        // Get direction from player to allay, then flip it
        Vec3 toAllay = allay.position().subtract(player.position());
        Vec3 opposite = player.position().subtract(toAllay);

        // Keep the distance similar but on the other side
        double dist = Math.max(ROAM_DIST_MIN, Math.min(ROAM_DIST_MAX, toAllay.horizontalDistance()));
        double yOffset = toAllay.y;

        // Calculate angle of the opposite direction
        double angle = Math.toDegrees(Math.atan2(-opposite.x + player.getX(), opposite.z - player.getZ()));
        double yawOffset = angle - player.getYRot();

        Vec3 candidate = positionFromAngles(player, yawOffset, dist, yOffset);
        return pushOutOfCone(player, candidate);
    }

    /**
     * Creates a world position at a given horizontal angle offset, distance,
     * and Y offset from the player.
     */
    private Vec3 positionFromAngles(ServerPlayer player, double yawOffsetDeg, double dist, double yOffset) {
        double totalYaw = Math.toRadians(player.getYRot() + yawOffsetDeg);
        double x = player.getX() - Math.sin(totalYaw) * dist;
        double z = player.getZ() + Math.cos(totalYaw) * dist;
        double y = player.getY() + 1.0 + yOffset;  // base at player + 1 block
        return new Vec3(x, y, z);
    }

    /**
     * If a position is inside the player's forward avoidance cone,
     * push it to the nearest edge of the cone. Otherwise return as-is.
     */
    private Vec3 pushOutOfCone(ServerPlayer player, Vec3 pos) {
        Vec3 playerPos = player.getEyePosition();
        Vec3 forward = player.getViewVector(1.0f);
        Vec3 toPos = pos.subtract(playerPos);
        double dist = toPos.length();

        // Enforce minimum distance sphere — push target outward if too close
        if (dist < AVOID_SPHERE_RADIUS) {
            Vec3 dir = dist > 0.1 ? toPos.normalize() : new Vec3(0, 0, -1);
            pos = playerPos.add(dir.scale(ROAM_DIST_MIN));
            toPos = pos.subtract(playerPos);
            dist = toPos.length();
        }
        if (dist < 0.5) return pos;

        Vec3 toPosNorm = toPos.normalize();
        double dot = forward.dot(toPosNorm);

        // If not in the cone, position is fine
        if (dot <= AVOID_CONE_COS) return pos;

        // In the cone — project to the nearest cone edge.
        // Rotate the direction away from forward until it's at the cone boundary.
        // Simple approach: lerp toward the "right" perpendicular of forward
        Vec3 right = new Vec3(-forward.z, 0, forward.x).normalize();

        // Determine which side is closer (left or right of forward)
        double rightDot = right.dot(toPosNorm);
        if (rightDot < 0) right = right.scale(-1);  // use left side if closer

        // Blend: push the direction toward the cone edge
        // AVOID_CONE_COS ≈ 0.34 (70°), we want the result to be just outside
        Vec3 pushed = forward.scale(AVOID_CONE_COS).add(right.scale(Math.sin(Math.toRadians(70.0))));
        pushed = pushed.normalize().scale(dist);

        return playerPos.add(pushed.x, toPos.y, pushed.z);
    }
}
