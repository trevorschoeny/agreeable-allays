package com.trevorschoeny.agreeableallays.mixin;

import com.trevorschoeny.agreeableallays.access.BrainAccessor;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.schedule.Activity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;
import java.util.Set;

/**
 * Accessor mixin that exposes Brain's private availableBehaviorsByPriority map.
 * Brain stores behaviors in: Map<priority, Map<Activity, Set<BehaviorControl>>>.
 * We need this to surgically replace specific vanilla behaviors in the idle activity.
 */
@Mixin(Brain.class)
public abstract class BrainAccessorMixin<E extends LivingEntity> implements BrainAccessor<E> {

    @Shadow
    @Final
    private Map<Integer, Map<Activity, Set<BehaviorControl<? super E>>>> availableBehaviorsByPriority;

    @Override
    public Map<Integer, Map<Activity, Set<BehaviorControl<? super E>>>> agreeableallays$getAvailableBehaviorsByPriority() {
        return this.availableBehaviorsByPriority;
    }
}
