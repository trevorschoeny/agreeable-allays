package com.trevorschoeny.agreeableallays.access;

/**
 * Duck interface for the Allay entity.
 * Cast any Allay instance to this to get/set the sitting state
 * added by AllaySynchedDataMixin.
 *
 * Usage: ((SittingAllay) allay).agreeableallays$isSitting()
 */
public interface SittingAllay {

    boolean agreeableallays$isSitting();

    void agreeableallays$setSitting(boolean sitting);
}
