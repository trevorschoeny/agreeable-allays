package com.trevorschoeny.agreeableallays.mixin;

import com.google.common.collect.ImmutableList;
import com.trevorschoeny.agreeableallays.AgreeableAllaysMemory;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.sensing.SensorType;
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.entity.animal.allay.Allay;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Injects our custom DETACH_TICKS_REMAINING memory into the Allay's brain provider.
 *
 * The Allay's brainProvider() passes its static MEMORY_TYPES and SENSOR_TYPES
 * to Brain.provider(). The Brain only accepts memories that were declared in
 * the provider — any attempt to set an undeclared memory throws. So we must
 * intercept brainProvider() and return a new provider whose memory list
 * includes our custom type.
 *
 * We shadow SENSOR_TYPES (unchanged) and MEMORY_TYPES (extended) and build
 * a fresh Brain.Provider that includes everything vanilla expects plus our type.
 */
@Mixin(Allay.class)
public abstract class AllayMemoryMixin {

    @Shadow
    @Final
    protected static ImmutableList<MemoryModuleType<?>> MEMORY_TYPES;

    @Shadow
    @Final
    protected static ImmutableList<SensorType<? extends Sensor<? super Allay>>> SENSOR_TYPES;

    /**
     * Replace the return value of brainProvider() with one that includes
     * our custom memory type alongside all of vanilla's.
     */
    @Inject(method = "brainProvider", at = @At("HEAD"), cancellable = true)
    protected void agreeableallays$extendBrainProvider(
            CallbackInfoReturnable<Brain.Provider<Allay>> cir) {

        // Build a new list: vanilla memories + our custom types
        List<MemoryModuleType<?>> extended = new ArrayList<>(MEMORY_TYPES);
        extended.add(AgreeableAllaysMemory.DETACH_TICKS_REMAINING);
        extended.add(AgreeableAllaysMemory.INTERESTED_IN_PLAYER);

        cir.setReturnValue(Brain.provider(extended, SENSOR_TYPES));
    }
}
