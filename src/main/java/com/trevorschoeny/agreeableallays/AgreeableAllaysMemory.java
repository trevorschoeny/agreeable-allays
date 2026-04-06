package com.trevorschoeny.agreeableallays;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

import java.util.Optional;

/**
 * Registers custom MemoryModuleTypes used by Agreeable Allays.
 *
 * DETACH_TICKS_REMAINING: 10-second (200 tick) countdown that starts when
 * a player takes the allay's held item. When it reaches zero the allay
 * unbonds from its owner. This gives the player a grace period to hand
 * the allay a new item before it wanders off.
 */
public class AgreeableAllaysMemory {

    // Countdown (in ticks) before the allay unbonds after its item is taken.
    // No codec — this memory is transient and does not need serialization
    // (the brain codec handles Optional.empty() gracefully).
    public static final MemoryModuleType<Integer> DETACH_TICKS_REMAINING =
            new MemoryModuleType<>(Optional.empty());

    // UUID of the player currently looking directly at this allay (15° cone).
    // When present, the allay flies to a position in front of that player's face,
    // overriding all state-specific positioning behaviors.
    public static final MemoryModuleType<java.util.UUID> INTERESTED_IN_PLAYER =
            new MemoryModuleType<>(Optional.empty());

    public static void register() {
        Registry.register(
                BuiltInRegistries.MEMORY_MODULE_TYPE,
                Identifier.fromNamespaceAndPath(AgreeableAllaysMod.MOD_ID, "detach_ticks_remaining"),
                DETACH_TICKS_REMAINING
        );
        Registry.register(
                BuiltInRegistries.MEMORY_MODULE_TYPE,
                Identifier.fromNamespaceAndPath(AgreeableAllaysMod.MOD_ID, "interested_in_player"),
                INTERESTED_IN_PLAYER
        );
    }
}
