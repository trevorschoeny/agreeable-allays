package com.trevorschoeny.agreeableallays.access;

import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.schedule.Activity;

import java.util.Map;
import java.util.Set;

/**
 * Accessor mixin interface for Brain's private availableBehaviorsByPriority field.
 * This lets us modify the behavior sets after vanilla has configured them,
 * which is how we replace GoAndGiveItemsToTarget with our custom behaviors.
 */
public interface BrainAccessor<E> {

    Map<Integer, Map<Activity, Set<BehaviorControl<? super E>>>> agreeableallays$getAvailableBehaviorsByPriority();
}
