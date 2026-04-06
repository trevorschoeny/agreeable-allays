package com.trevorschoeny.agreeableallays.mixin;

import com.trevorschoeny.agreeableallays.access.BrainAccessor;
import com.trevorschoeny.agreeableallays.behavior.DeliverToInventoryBehavior;
import com.trevorschoeny.agreeableallays.behavior.StayByOwnerBehavior;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.GoAndGiveItemsToTarget;
import net.minecraft.world.entity.ai.behavior.RandomStroll;
import net.minecraft.world.entity.ai.behavior.RunOne;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.animal.allay.AllayAi;
import net.minecraft.world.entity.schedule.Activity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.Set;

/**
 * Modifies the allay's brain to replace vanilla's item-throwing and random
 * wandering with our companion-positioning and inventory-delivery behaviors.
 *
 * We inject at RETURN of initIdleActivity — at that point vanilla has already
 * added all idle behaviors to the brain's internal priority map. We then
 * surgically swap out:
 *
 *   Priority 1: GoAndGiveItemsToTarget → DeliverToInventoryBehavior
 *     (silent inventory insertion instead of throwing items at the player)
 *
 *   Priority 4: RunOne[RandomStroll, SetWalkTarget, DoNothing] → StayByOwnerBehavior
 *     (companion positioning instead of aimless wandering)
 *
 * Everything else (GoToWantedItem, StayCloseToTarget, SetEntityLookTarget)
 * stays intact — vanilla item retrieval and look-around animations are fine.
 */
@Mixin(AllayAi.class)
public abstract class AllayAiBrainMixin {

    /**
     * After vanilla finishes configuring the idle activity, replace the
     * item-throwing and wandering behaviors with our companion versions.
     *
     * Brain stores behaviors as: Map<priority, Map<Activity, Set<BehaviorControl>>>
     * The idle activity lives under Activity.IDLE at each priority level.
     */
    @Inject(method = "initIdleActivity", at = @At("RETURN"))
    private static void agreeableallays$replaceIdleBehaviors(
            Brain<Allay> brain, CallbackInfo ci) {

        @SuppressWarnings("unchecked")
        BrainAccessor<Allay> accessor = (BrainAccessor<Allay>) (Object) brain;
        Map<Integer, Map<Activity, Set<BehaviorControl<? super Allay>>>> byPriority =
                accessor.agreeableallays$getAvailableBehaviorsByPriority();

        // Priority 1: Replace GoAndGiveItemsToTarget with DeliverToInventoryBehavior
        replaceBehaviorAtPriority(byPriority, 1, GoAndGiveItemsToTarget.class,
                new DeliverToInventoryBehavior());

        // Priority 2: Replace StayCloseToTarget with StayByOwnerBehavior.
        // Vanilla StayCloseToTarget uses a BehaviorBuilder, so it's not a named class —
        // we remove whatever is at priority 2 and replace with our companion positioning.
        replaceAnyBehaviorAtPriority(byPriority, 2, new StayByOwnerBehavior());

        // Priority 4: Replace RunOne (random wandering group) with standalone
        // RandomStroll.fly. RunOne contained RandomStroll + SetWalkTarget + DoNothing
        // bundled together — we remove the bundle but keep RandomStroll.fly so that
        // wild (unbonded) allays still fly around naturally. StayByOwnerBehavior at
        // priority 2 handles positioning for bonded allays and will override this.
        replaceBehaviorAtPriority(byPriority, 4, RunOne.class, null);

        // Re-add RandomStroll.fly as a standalone behavior at priority 4
        Map<Activity, Set<BehaviorControl<? super Allay>>> activitiesAt4 = byPriority.get(4);
        if (activitiesAt4 != null) {
            Set<BehaviorControl<? super Allay>> idleBehaviors4 = activitiesAt4.get(Activity.IDLE);
            if (idleBehaviors4 != null) {
                idleBehaviors4.add(RandomStroll.fly(1.0f));
            }
        }
    }

    /**
     * Finds and replaces a behavior of the given type at a specific priority.
     * If replacement is null, just removes the behavior.
     */
    private static boolean replaceBehaviorAtPriority(
            Map<Integer, Map<Activity, Set<BehaviorControl<? super Allay>>>> byPriority,
            int priority,
            Class<?> targetType,
            BehaviorControl<? super Allay> replacement) {

        Map<Activity, Set<BehaviorControl<? super Allay>>> activitiesAtPriority =
                byPriority.get(priority);
        if (activitiesAtPriority == null) return false;

        Set<BehaviorControl<? super Allay>> idleBehaviors =
                activitiesAtPriority.get(Activity.IDLE);
        if (idleBehaviors == null) return false;

        BehaviorControl<? super Allay> toRemove = null;
        for (BehaviorControl<? super Allay> behavior : idleBehaviors) {
            if (targetType.isInstance(behavior)) {
                toRemove = behavior;
                break;
            }
        }

        if (toRemove != null) {
            idleBehaviors.remove(toRemove);
            if (replacement != null) {
                idleBehaviors.add(replacement);
            }
            return true;
        }
        return false;
    }

    /**
     * Replaces whatever single behavior exists at a priority with the given replacement.
     * Used for declarative behaviors (BehaviorBuilder products) that don't have a named class.
     */
    private static boolean replaceAnyBehaviorAtPriority(
            Map<Integer, Map<Activity, Set<BehaviorControl<? super Allay>>>> byPriority,
            int priority,
            BehaviorControl<? super Allay> replacement) {

        Map<Activity, Set<BehaviorControl<? super Allay>>> activitiesAtPriority =
                byPriority.get(priority);
        if (activitiesAtPriority == null) return false;

        Set<BehaviorControl<? super Allay>> idleBehaviors =
                activitiesAtPriority.get(Activity.IDLE);
        if (idleBehaviors == null || idleBehaviors.isEmpty()) return false;

        // Remove all existing behaviors at this priority and replace
        idleBehaviors.clear();
        idleBehaviors.add(replacement);
        return true;
    }
}
