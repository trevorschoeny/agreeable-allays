package com.trevorschoeny.agreeableallays.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Makes allays fully ghostly to players — no collision, no push, can fly
 * through the player's hitbox.
 *
 * Three layers of collision removal:
 * 1. canCollideWith() → prevents push physics between allay and player
 * 2. canBeCollidedWith() → makes the allay non-solid to other entities
 * 3. isPushable() → prevents any external force from pushing the allay
 */
@Mixin(Allay.class)
public abstract class AllayCollisionMixin extends PathfinderMob {

    protected AllayCollisionMixin(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
    }

    /**
     * Allays cannot collide with any player — prevents push interactions.
     */
    @Override
    public boolean canCollideWith(Entity other) {
        if (other instanceof Player) {
            return false;
        }
        return super.canCollideWith(other);
    }

    /**
     * Allays are not solid to other entities — players can walk/fly through them
     * and the allay's pathfinding won't be blocked by player hitboxes.
     */
    @Override
    public boolean canBeCollidedWith(Entity entity) {
        if (entity instanceof Player) {
            return false;
        }
        return super.canBeCollidedWith(entity);
    }

    /**
     * Allays cannot be pushed by anything — prevents physics interactions
     * from knocking them off course.
     */
    @Override
    public boolean isPushable() {
        return false;
    }
}
