package com.trevorschoeny.agreeableallays.mixin;

import com.trevorschoeny.agreeableallays.AgreeableAllaysMemory;
import com.trevorschoeny.agreeableallays.access.SittingAllay;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.allay.Allay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Spawns ambient particles around bonded allays to visually communicate
 * their current state. Particles are spawned server-side (since Brain
 * memories are server-only) and broadcast to nearby clients.
 *
 * Particle mapping by state (highest priority first):
 *   - INTERESTED_IN_PLAYER: heart particles (allay is being looked at)
 *   - Sitting: smoke particles (allay is resting)
 *   - Detaching: ash particles (fading away, frequency decreases over time)
 *   - Carrying items: happy villager particles (delivering)
 *   - Default bonded: enchant particles (soft companion glow)
 */
@Mixin(Allay.class)
public abstract class AllayParticleMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void agreeableallays$spawnStateParticles(CallbackInfo ci) {
        Allay allay = (Allay) (Object) this;

        // Server-side only — particles need sendParticles which is on ServerLevel
        if (!(allay.level() instanceof ServerLevel serverLevel)) return;

        // Only emit particles every 10 ticks (0.5 seconds) to avoid spam
        if (allay.tickCount % 10 != 0) return;

        Brain<Allay> brain = allay.getBrain();

        // Interested particles show for wild AND bonded allays.
        // All other particles require bonded state.
        boolean bonded = brain.hasMemoryValue(MemoryModuleType.LIKED_PLAYER);
        boolean interested = brain.hasMemoryValue(AgreeableAllaysMemory.INTERESTED_IN_PLAYER);
        if (!bonded && !interested) return;

        double x = allay.getX();
        double y = allay.getY() + 0.5;
        double z = allay.getZ();

        // Small random offset so particles don't stack at the exact center
        double ox = (allay.getRandom().nextDouble() - 0.5) * 0.5;
        double oz = (allay.getRandom().nextDouble() - 0.5) * 0.5;

        ParticleOptions particle;

        if (interested) {
            // Player is looking directly at this allay — tiny floating sparkles
            particle = ParticleTypes.END_ROD;

        } else if (((SittingAllay) allay).agreeableallays$isSitting()) {
            // Allay is sitting/resting
            particle = ParticleTypes.SCRAPE;

        } else if (brain.hasMemoryValue(AgreeableAllaysMemory.DETACH_TICKS_REMAINING)) {
            // Allay is in the detach countdown — particles fade out as time runs down.
            // Higher ticks remaining = more likely to emit a particle.
            int ticks = brain.getMemory(AgreeableAllaysMemory.DETACH_TICKS_REMAINING).orElse(0);
            if (allay.getRandom().nextFloat() > (ticks / 200.0f)) return;
            particle = ParticleTypes.ASH;

        } else if (!allay.getInventory().isEmpty()) {
            // Carrying items — on the way to deliver
            particle = ParticleTypes.HAPPY_VILLAGER;

        } else {
            // Default companion state — soft enchant glow
            particle = ParticleTypes.ENCHANT;
        }

        // sendParticles broadcasts to all nearby clients
        serverLevel.sendParticles(particle, x + ox, y, z + oz, 1, 0, 0, 0, 0);
    }
}
