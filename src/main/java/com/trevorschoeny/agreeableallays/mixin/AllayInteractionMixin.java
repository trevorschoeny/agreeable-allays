package com.trevorschoeny.agreeableallays.mixin;

import com.trevorschoeny.agreeableallays.AgreeableAllaysMemory;
import com.trevorschoeny.agreeableallays.access.SittingAllay;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts Allay.mobInteract() at HEAD to implement companion interactions:
 *
 * 1. SHIFT-RIGHT-CLICK on bonded allay: toggle sit/stay
 * 2. EMPTY HAND on bonded allay: take item + start 10-second detach timer
 * 3. ITEM IN HAND on bonded allay: swap items (allay gets new, player gets old)
 * 4. ITEM IN HAND on unbonded allay during detach window: cancel detach, fall
 *    through to vanilla which re-bonds via LIKED_PLAYER
 *
 * All cases that set a return value cancel the vanilla handler so we don't
 * double-process the interaction.
 */
@Mixin(Allay.class)
public abstract class AllayInteractionMixin {

    @Inject(method = "mobInteract", at = @At("HEAD"), cancellable = true)
    private void agreeableallays$handleInteraction(
            Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {

        // Only handle main-hand interactions — offhand can fire a second
        // mobInteract call that causes double-swaps or items landing in the offhand.
        if (hand != InteractionHand.MAIN_HAND) return;

        Allay allay = (Allay) (Object) this;
        SittingAllay sittingAllay = (SittingAllay) allay;
        ItemStack playerItem = player.getItemInHand(hand);
        Brain<Allay> brain = allay.getBrain();

        // Check if this player is the allay's bonded owner.
        // In singleplayer/dev, UUIDs can mismatch — if any LIKED_PLAYER is set
        // and this is the only player, treat as bonded.
        boolean isBonded = brain.getMemory(MemoryModuleType.LIKED_PLAYER)
                .map(uuid -> {
                    if (uuid.equals(player.getUUID())) return true;
                    // Singleplayer fallback: if LIKED_PLAYER is set and we're the only player
                    if (allay.level().getServer() != null && allay.level().getServer().getPlayerList().getPlayers().size() == 1) return true;
                    return false;
                })
                .orElse(false);

        // --- 1. SHIFT-RIGHT-CLICK: toggle sit/stay (companion only) ---
        if (player.isShiftKeyDown() && isBonded) {
            boolean newSitting = !sittingAllay.agreeableallays$isSitting();
            sittingAllay.agreeableallays$setSitting(newSitting);
            // TODO: play sit/unsit sound
            cir.setReturnValue(InteractionResult.SUCCESS);
            return;
        }

        // --- 2. EMPTY HAND on companion: take item + start detach timer ---
        // Vanilla stores the allay's filter item in its MAIN_HAND, not inventory.
        if (playerItem.isEmpty() && isBonded) {
            ItemStack allayItem = allay.getItemInHand(InteractionHand.MAIN_HAND);
            if (!allayItem.isEmpty()) {
                // Move the allay's held item into the player's hand
                player.setItemInHand(hand, allayItem.copy());
                allay.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

                // Start 10-second (200 tick) detach countdown.
                brain.setMemory(AgreeableAllaysMemory.DETACH_TICKS_REMAINING, 200);

                // TODO: play take-item sound
                cir.setReturnValue(InteractionResult.SUCCESS);
                return;
            }
        }

        // --- 3. ITEM IN HAND on companion: swap items ---
        // Vanilla stores the allay's filter item in its MAIN_HAND, not inventory.
        if (!playerItem.isEmpty() && isBonded) {
            ItemStack allayItem = allay.getItemInHand(InteractionHand.MAIN_HAND);
            if (!allayItem.isEmpty()) {
                // Give allay one of the player's item
                ItemStack newAllayItem = playerItem.copy();
                newAllayItem.setCount(1);
                allay.setItemInHand(InteractionHand.MAIN_HAND, newAllayItem);
                playerItem.shrink(1);

                // Return old allay item to player, dropping if inventory is full
                if (!player.getInventory().add(allayItem.copy())) {
                    player.drop(allayItem.copy(), false);
                }

                // TODO: play swap sound
                cir.setReturnValue(InteractionResult.SUCCESS);
                return;
            }
        }

        // --- 4. ITEM IN HAND on unbonded allay: cancel detach if in linger window ---
        // Vanilla will handle the actual give-item + set LIKED_PLAYER bonding.
        // We just clear the detach timer so the re-bond sticks.
        if (!playerItem.isEmpty() && !isBonded) {
            brain.getMemory(AgreeableAllaysMemory.DETACH_TICKS_REMAINING).ifPresent(ticks -> {
                brain.eraseMemory(AgreeableAllaysMemory.DETACH_TICKS_REMAINING);
            });
            // Fall through to vanilla handler
        }
    }
}
