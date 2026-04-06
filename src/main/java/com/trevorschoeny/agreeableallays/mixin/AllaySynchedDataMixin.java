package com.trevorschoeny.agreeableallays.mixin;

import com.trevorschoeny.agreeableallays.access.SittingAllay;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a synched DATA_SITTING boolean to the Allay entity.
 * This value is automatically replicated to all clients via the
 * SynchedEntityData system, so renderers can check sitting state
 * without extra packets.
 *
 * Implements SittingAllay duck interface for cross-mixin access.
 */
@Mixin(Allay.class)
public abstract class AllaySynchedDataMixin extends PathfinderMob implements SittingAllay {

    // Synched boolean — false by default. When true the allay is sitting
    // and should not move or pick up items.
    @Unique
    private static final EntityDataAccessor<Boolean> DATA_SITTING =
            SynchedEntityData.defineId(Allay.class, EntityDataSerializers.BOOLEAN);

    // Required by extending PathfinderMob
    protected AllaySynchedDataMixin(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
    }

    // Register the accessor at the tail of vanilla's defineSynchedData
    @Inject(method = "defineSynchedData", at = @At("TAIL"))
    private void agreeableallays$defineSynchedData(SynchedEntityData.Builder builder, CallbackInfo ci) {
        builder.define(DATA_SITTING, false);
    }

    // --- SittingAllay duck interface implementation ---

    @Override
    public boolean agreeableallays$isSitting() {
        return this.entityData.get(DATA_SITTING);
    }

    @Override
    public void agreeableallays$setSitting(boolean sitting) {
        this.entityData.set(DATA_SITTING, sitting);
    }
}
