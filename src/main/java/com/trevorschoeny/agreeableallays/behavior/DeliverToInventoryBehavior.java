package com.trevorschoeny.agreeableallays.behavior;

import com.trevorschoeny.agreeableallays.AgreeableAllaysMemory;
import com.trevorschoeny.agreeableallays.access.SittingAllay;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.animal.allay.Allay;
import com.trevorschoeny.agreeableallays.AllayHelper;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.Optional;

/**
 * Silently inserts items into the player's inventory when the allay is close enough.
 *
 * This replaces vanilla's GoAndGiveItemsToTarget, which flies to the player's
 * face and throws items on the ground. Instead, items are directly inserted into
 * the player's inventory (like a quiet pocket delivery), and any overflow is
 * dropped at the allay's feet.
 *
 * Fires as a one-shot when:
 *   1. The allay has items in its inventory
 *   2. It has a bonded player (LIKED_PLAYER)
 *   3. It's within 4 blocks of that player
 *   4. It's not sitting
 *
 * After delivery, sets a 60-tick (3 second) pickup cooldown so the allay
 * doesn't immediately re-grab items it just delivered.
 */
public class DeliverToInventoryBehavior extends Behavior<Allay> {

    // Must be within this distance to deliver (same as vanilla's CLOSE_ENOUGH_TO_TARGET)
    private static final double DELIVERY_RANGE = 4.0;

    // Cooldown after delivery before the allay picks up more items (ticks)
    private static final int POST_DELIVERY_COOLDOWN = 60;

    public DeliverToInventoryBehavior() {
        // One-shot behavior: min and max duration of 1 tick
        // Preconditions: must have a liked player
        super(
                Map.of(
                        MemoryModuleType.LIKED_PLAYER, MemoryStatus.VALUE_PRESENT,
                        AgreeableAllaysMemory.INTERESTED_IN_PLAYER, MemoryStatus.VALUE_ABSENT
                ),
                1, // min duration — fires once
                1  // max duration — fires once
        );
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, Allay allay) {
        // Don't deliver if sitting
        if (((SittingAllay) allay).agreeableallays$isSitting()) {
            return false;
        }

        // Must have items to deliver
        if (allay.getInventory().isEmpty()) {
            return false;
        }

        // Must have a valid player within delivery range
        Optional<ServerPlayer> optPlayer = AllayHelper.getLikedPlayer(allay);
        if (optPlayer.isEmpty()) {
            return false;
        }

        ServerPlayer player = optPlayer.get();
        return allay.distanceTo(player) <= DELIVERY_RANGE;
    }

    @Override
    protected void start(ServerLevel level, Allay allay, long gameTime) {
        Optional<ServerPlayer> optPlayer = AllayHelper.getLikedPlayer(allay);
        if (optPlayer.isEmpty()) {
            return;
        }

        ServerPlayer player = optPlayer.get();
        SimpleContainer inventory = allay.getInventory();

        // Transfer each slot from allay inventory into player inventory
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }

            // Try to insert into player inventory
            if (!player.getInventory().add(stack.copy())) {
                // Player inventory is full — drop the remainder at the allay's feet
                allay.spawnAtLocation(level, stack);
            }

            // Clear the allay's slot regardless (items are either in player inv or on ground)
            inventory.setItem(slot, ItemStack.EMPTY);
        }

        // Set a pickup cooldown so the allay doesn't immediately re-grab items
        allay.getBrain().setMemory(
                MemoryModuleType.ITEM_PICKUP_COOLDOWN_TICKS,
                POST_DELIVERY_COOLDOWN
        );
    }
}
