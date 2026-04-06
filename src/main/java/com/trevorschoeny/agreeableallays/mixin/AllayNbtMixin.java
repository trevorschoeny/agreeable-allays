package com.trevorschoeny.agreeableallays.mixin;

import com.trevorschoeny.agreeableallays.access.SittingAllay;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Persists the sitting state in the Allay's NBT data.
 *
 * 1.21.11 uses ValueOutput/ValueInput (not CompoundTag) for entity
 * serialization. We write a single boolean keyed by our mod id to
 * avoid collisions with vanilla or other mods.
 */
@Mixin(Allay.class)
public abstract class AllayNbtMixin {

    private static final String SITTING_KEY = "agreeableallays_sitting";

    // Save sitting state when the allay is written to disk
    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void agreeableallays$saveSitting(ValueOutput output, CallbackInfo ci) {
        boolean sitting = ((SittingAllay) this).agreeableallays$isSitting();
        // Only write if true — saves a tiny bit of space for the common case
        if (sitting) {
            output.putBoolean(SITTING_KEY, true);
        }
    }

    // Restore sitting state when the allay is loaded from disk
    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void agreeableallays$loadSitting(ValueInput input, CallbackInfo ci) {
        // getBooleanOr returns the default (false) if the key is absent,
        // so allays that were never told to sit load correctly.
        boolean sitting = input.getBooleanOr(SITTING_KEY, false);
        ((SittingAllay) this).agreeableallays$setSitting(sitting);
    }
}
