package com.trevorschoeny.agreeableallays.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.allay.Allay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes bonded allays immune to fire and lava damage.
 *
 * When an allay has a LIKED_PLAYER (bonded to someone), all fire-type
 * damage is cancelled. This covers IN_FIRE, ON_FIRE, LAVA, and HOT_FLOOR
 * via the IS_FIRE damage tag. We also clear fire ticks so the allay
 * doesn't visually burn.
 *
 * Allay overrides hurtServer directly, so we can inject into it.
 */
@Mixin(Allay.class)
public abstract class AllayDamageMixin {

    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void agreeableallays$cancelFireDamage(
            ServerLevel serverLevel, DamageSource source, float amount,
            CallbackInfoReturnable<Boolean> cir) {

        Allay allay = (Allay) (Object) this;

        // Only protect bonded allays (those with a liked player)
        if (!allay.getBrain().hasMemoryValue(MemoryModuleType.LIKED_PLAYER)) return;

        // Cancel all fire-type damage: IN_FIRE, ON_FIRE, LAVA, HOT_FLOOR
        if (source.is(DamageTypeTags.IS_FIRE)) {
            // Clear fire ticks so the allay doesn't visually burn.
            // setRemainingFireTicks is synced to the client automatically.
            allay.setRemainingFireTicks(0);
            cir.setReturnValue(false);
        }
    }
}
