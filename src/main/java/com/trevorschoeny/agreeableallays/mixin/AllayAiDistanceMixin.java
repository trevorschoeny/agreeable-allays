package com.trevorschoeny.agreeableallays.mixin;

import net.minecraft.world.entity.animal.allay.AllayAi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Removes the 64-block amnesia from AllayAi.getLikedPlayer().
 *
 * Vanilla loads 64.0D and calls player.closerThan(allay, 64.0D).
 * If the player is further than 64 blocks, getLikedPlayer() returns
 * Optional.empty(), which causes every brain behavior that depends
 * on the liked player to silently stop — the allay effectively
 * "forgets" its owner.
 *
 * We replace the 64.0D constant with Double.MAX_VALUE so the distance
 * check always passes. The only way to unbond is now by taking the
 * allay's held item (which starts the 10-second detach timer).
 */
@Mixin(AllayAi.class)
public abstract class AllayAiDistanceMixin {

    /**
     * Replace the 64.0D distance threshold in getLikedPlayer() with
     * Double.MAX_VALUE so the allay never forgets its owner due to distance.
     *
     * Bytecode context (1.21.11):
     *   ldc2_w 64.0D
     *   invokevirtual ServerPlayer.closerThan(Entity, double)Z
     *
     * @ModifyConstant targets the double literal 64.0 in getLikedPlayer.
     */
    @ModifyConstant(
            method = "getLikedPlayer",
            constant = @Constant(doubleValue = 64.0D)
    )
    private static double agreeableallays$removeDistanceLimit(double original) {
        // Effectively infinite — closerThan() will always return true
        return Double.MAX_VALUE;
    }
}
