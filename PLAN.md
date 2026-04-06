# Agreeable Allays — Implementation Plan

## Overview

Seven features, ordered by dependency. Each phase builds on the previous and can be tested independently.

---

## Phase 1: Custom Memory Types + Sit/Stay State

**Why first**: Everything else depends on being able to store custom state on the allay entity. We need a `SITTING` synched data flag (for client rendering) and a `DETACH_TIMER` field, plus we need to register a custom `MemoryModuleType` for our companion behavior.

**Files to create**:
- `mixin/AllaySynchedDataMixin.java` — inject into `Allay.defineSynchedData()` at TAIL to add:
  - `DATA_SITTING` (`EntityDataAccessor<Boolean>`, default false) — synched so client knows to render sitting pose
- `mixin/AllayNbtMixin.java` — inject into `addAdditionalSaveData()` and `readAdditionalSaveData()` to persist:
  - `agreeableallays_sitting` (boolean)
- `AgreeableAllaysMemory.java` — register custom `MemoryModuleType`:
  - `DETACH_TICKS_REMAINING` (Integer) — countdown for 10-second linger (200 ticks)
  - Register in `onInitialize()` via Fabric's registry

**Mixin targets**:
- `Allay.defineSynchedData(SynchedEntityData.Builder)` — `@Inject` at TAIL
- `Allay.addAdditionalSaveData(ValueOutput)` — `@Inject` at TAIL  
- `Allay.readAdditionalSaveData(ValueInput)` — `@Inject` at TAIL

**Test**: Give an allay an item, verify sitting state can be toggled and survives relog.

---

## Phase 2: Interaction Model (Swap, Sit/Stay, Graceful Detach)

**Why second**: The interaction model is the player-facing API — how you communicate with your allay. Getting this right early means we can test everything else through natural gameplay.

**Files to create**:
- `mixin/AllayInteractionMixin.java` — inject into `Allay.mobInteract(Player, InteractionHand)` at HEAD with `@Inject(cancellable = true)`:

**Interaction logic (all checked before vanilla handler runs)**:

```
if (player.isShiftKeyDown()):
    if (allay is bonded to this player):
        toggle DATA_SITTING
        play sit/unsit sound
        cancel vanilla handler
        return SUCCESS

if (player hand is empty):
    if (allay is bonded to this player AND allay has item):
        move allay's item to player's hand
        start DETACH_TICKS_REMAINING = 200 (10 seconds)
        if sitting, keep sitting (detach timer will handle unsit)
        play take-item sound
        cancel vanilla handler
        return SUCCESS

if (player hand has item):
    if (allay is bonded to this player AND allay has item):
        // SWAP
        old_item = allay's item
        allay's item = copy of player's hand item (1 count)
        player's hand item shrink(1)
        add old_item to player inventory (or drop if full)
        play swap sound
        cancel vanilla handler
        return SUCCESS
    // else: fall through to vanilla (gives item, bonds allay)
```

**Detach timer tick logic** (mixin into `Allay.tick()` or `customServerAiStep()`):
- If `DETACH_TICKS_REMAINING` memory is set and > 0: decrement
- When it hits 0:
  - Erase `LIKED_PLAYER` memory
  - Set `DATA_SITTING` to false
  - Erase `DETACH_TICKS_REMAINING`

**Test**: All four interaction types work. Swap puts old item in inventory. Detach timer counts down and unbonds+unsits.

---

## Phase 3: Collision Removal

**Why third**: Quick win, standalone, no dependencies on other phases.

**Files to create**:
- `mixin/AllayCollisionMixin.java` — inject into `Allay.isPushable()` or the pushback method

**Approach**: Two options, both simple:
1. **Option A**: Mixin on `Allay.pushAway(Entity)` — cancel when the entity is a `Player`
2. **Option B**: Mixin on `LivingEntity.isPushable()` specifically for Allay — return false when the pusher is a Player

Option A is more targeted. The "Allay, behave!" mod uses a `LivingEntityMixin` that cancels `pushAway()` — we should follow the same pattern but apply it to ALL players, not just the liked player.

Also consider: `Allay.isPickable()` — if we return false when a player is looking, allays won't intercept click/attack raycasts. This might be too aggressive though (you still need to right-click them). We may want to only suppress `pushAway` and leave picking intact.

**Test**: Stand next to an allay, walk into it. It should not move. You should not be pushed.

---

## Phase 4: Companion Positioning (The Big One)

**Why fourth**: This is the core companion feel. Requires the Brain system to be modified.

**Approach**: Replace the idle wandering behavior with a "stay near owner" behavior, and replace the item delivery behavior with inventory insertion.

**Files to create**:
- `behavior/StayByOwnerBehavior.java` — custom `Behavior<Allay>`:
  - **Start condition**: allay has `LIKED_PLAYER` AND is not `DATA_SITTING` AND is not retrieving an item AND is not panicking
  - **Target position**: calculate a point to the **side and slightly behind** the player:
    - Use player's look direction (yaw)
    - Offset 90-135 degrees to the side, ~3-4 blocks away
    - Add slight randomness so it feels organic, not robotic
    - For multiple allays: offset the angle per-allay (use entity ID as seed) to spread them
  - **Movement**: set `WALK_TARGET` to the calculated position with speed 2.25f
  - **Idle drift**: occasionally (every 60-120 ticks), recalculate position with slight angle variation so the allay drifts naturally into and out of peripheral vision
  - **Stop condition**: `LIKED_PLAYER` cleared, or sitting, or item to retrieve

- `behavior/DeliverToInventoryBehavior.java` — replaces `GoAndGiveItemsToTarget`:
  - **Start condition**: allay has picked-up items in inventory AND `LIKED_PLAYER` is set AND is close to player (within 4 blocks)
  - **Action**: call `player.getInventory().add(itemStack)` server-side
  - If `add()` returns false (full), drop the item: `allay.spawnAtLocation(itemStack)`
  - Set `ITEM_PICKUP_COOLDOWN_TICKS` after delivery
  - **No throw animation** — items just appear in inventory

**Brain modification** (mixin into `AllayAi`):
- `mixin/AllayAiBrainMixin.java` — `@Inject` into `AllayAi.makeBrain()` at RETURN:
  - Get the brain from return value
  - Replace the idle activity behaviors:
    - Keep `GoToWantedItem` (item retrieval stays vanilla)
    - Replace `GoAndGiveItemsToTarget` with `DeliverToInventoryBehavior`
    - Replace `RandomStrollLand` with `StayByOwnerBehavior`
    - Keep `RandomLookaround`

- Also modify `AllayAi.updateActivity()` if needed — the vanilla logic switches activities based on state, and we may need to adjust when `GIVE_ITEMS_TO_PLAYER` activates (since delivery now happens passively when close, not as a targeted navigation).

**The positioning algorithm** (important design detail):
```
playerYaw = player.getYRot()  // degrees, 0 = south, 90 = west
offsetAngle = playerYaw + 110  // slightly behind and to the right
// For multiple allays: offsetAngle += (entityId % 4) * 30  // spread them

targetX = player.getX() + sin(offsetAngle) * 3.5
targetZ = player.getZ() + cos(offsetAngle) * 3.5
targetY = player.getY() + 0.5  // slightly above eye level

// Add gentle noise so they bob and drift
targetX += sin(tickCount * 0.05) * 0.5
targetY += sin(tickCount * 0.03) * 0.3
```

**Test**: Bond an allay, walk around. It should hover to your side/behind. Give it a matching item on the ground — it leaves to fetch it, comes back, item appears in your inventory. Sit it — it stops following. Unsit — it returns.

---

## Phase 5: Teleportation

**Why fifth**: Needs companion positioning working first so the allay has somewhere to teleport TO.

**Files to create**:
- `behavior/TeleportToOwnerBehavior.java` — custom `Behavior<Allay>` (or inject into `Allay.tick()`):
  - **Check every tick** (cheap distance check): if distance to liked player > 64 blocks
  - **Teleport algorithm** (adapted from `TamableAnimal.teleportToAroundBlockPos()`):
    - 10 attempts at random positions around the player
    - Offset: X/Z ±3 blocks, Y ±1 block
    - Safety: check block below is solid OR allay can fly (allays can fly, so we may relax the ground check — just ensure not inside a solid block)
    - On success: set position directly, play teleport sound
  - **Skip if sitting**: sitting allays do NOT teleport (they stay put)
  - **Skip during detach**: don't teleport during 10-second linger

**Also**: Remove the 64-block amnesia. Mixin into `AllayAi.getLikedPlayer()` — the vanilla method returns empty if the player is >64 blocks away. We need to either:
- `@Overwrite` the distance check (risky)
- `@ModifyConstant` to change 64 to something huge (cleaner)
- `@Inject` at HEAD and handle the lookup ourselves, canceling the vanilla method

`@ModifyConstant` is cleanest if there's a literal 64 in the method. Need to verify — the research says `getLikedPlayer()` validates player is within 64 blocks.

**Test**: Bond an allay, fly 100 blocks away with elytra. Allay should teleport to your side. Sit the allay first — it should NOT teleport.

---

## Phase 6: Sitting Behavior Suppression

**Why sixth**: Needs everything else working so we can verify sitting properly suppresses all active behaviors.

**What sitting suppresses**:
- `StayByOwnerBehavior` — check `DATA_SITTING` in start condition, don't start if true
- `GoToWantedItem` — mixin to add sitting check, OR wrap the predicate
- `GoAndGiveItemsToTarget` / `DeliverToInventoryBehavior` — don't deliver when sitting
- `TeleportToOwnerBehavior` — don't teleport when sitting
- `RandomStrollLand` — don't wander when sitting (already replaced by StayByOwnerBehavior)

**What sitting allows**:
- Passive item pickup — items that land within the allay's `ITEM_PICKUP_REACH` (1,1,1) are still collected. This is vanilla's `wantsToPickUp()` + entity collision pickup, which happens regardless of Brain behaviors.

**Positioning when sitting**: The allay hovers at its current position. We may need to set `WALK_TARGET` to the allay's own position (or just not set any walk target) so it stays put.

**Test**: Sit an allay, throw matching items at it — it picks them up. Throw items far away — it ignores them. Walk away — it stays. Fly 100 blocks — it stays (no teleport).

---

## Phase 7: Sound Cues + Polish

**Why last**: Polish layer, no functional dependencies.

**Sounds to add**:
- Sit command: short, gentle chime (could reuse amethyst cluster sound, pitched up)
- Unsit command: reverse chime or slightly different pitch
- Item swap: distinct click/chime (different from vanilla give-item)
- Teleport arrival: soft whoosh or shimmer (so you know it caught up)

**Sound registration**:
- Register custom `SoundEvent`s in `onInitialize()`
- Add sound JSON files in `assets/agreeable-allays/sounds/`
- Or: reuse vanilla sounds with pitch/volume modifiers (simpler, no custom assets needed)

**Starting with vanilla sound reuse** is probably smart — we can replace with custom sounds later.

**Other polish**:
- Idle animation: the allay already has a gentle bob. We may not need to add anything — just ensure the positioning behavior creates natural-feeling drift
- Verify all interactions show correct hand swing animation
- Verify item swap shows the new item in the allay's hand immediately (SynchedEntityData should handle this)

---

## File Summary

### New Java files (7):
| File | Purpose |
|------|---------|
| `AgreeableAllaysMemory.java` | Custom MemoryModuleType registration |
| `behavior/StayByOwnerBehavior.java` | Companion positioning (side/behind player) |
| `behavior/DeliverToInventoryBehavior.java` | Direct inventory item insertion |
| `behavior/TeleportToOwnerBehavior.java` | 64-block teleport-to-owner |
| `mixin/AllaySynchedDataMixin.java` | DATA_SITTING synched flag |
| `mixin/AllayNbtMixin.java` | Persist sitting state in NBT |
| `mixin/AllayInteractionMixin.java` | Swap, sit/stay, graceful detach interactions |

### New mixin files (4 more):
| File | Purpose |
|------|---------|
| `mixin/AllayAiBrainMixin.java` | Replace brain behaviors |
| `mixin/AllayCollisionMixin.java` | Remove player collision |
| `mixin/AllayTickMixin.java` | Detach timer countdown |
| `mixin/AllayAiDistanceMixin.java` | Remove 64-block amnesia |

### Modified existing files (3):
| File | Change |
|------|--------|
| `AgreeableAllaysMod.java` | Register MemoryModuleTypes, SoundEvents |
| `agreeable-allays.mixins.json` | Add all mixin references |
| `fabric.mod.json` | No changes expected |

### Total: ~11 new files, ~3 modified files

---

## Open Questions (To Resolve During Implementation)

1. **Brain behavior replacement technique**: `@ModifyArg` on the activity list (like "Allay, behave!" does) vs `@Inject` at RETURN of `makeBrain()` and rebuilding activities. Need to see which is cleaner for our scope.

2. **Custom MemoryModuleType registration timing**: Fabric's registry may require this before any Allay entities load. Verify `onInitialize()` is early enough.

3. **Sitting visual**: Does setting `DATA_SITTING` need a custom renderer, or can we just stop movement and let the allay hover in place? Hovering-in-place probably looks fine — allays don't have legs to tuck.

4. **Activity system changes**: Vanilla's `updateActivity()` switches between 4 activities. Our `DeliverToInventoryBehavior` makes the `GIVE_ITEMS_TO_PLAYER` activity mostly obsolete (delivery is passive). We may need to adjust activity selection so the allay doesn't constantly try to switch to a delivery activity.

5. **Multiple allays angle spread**: The entity-ID-based angle offset is a rough idea. May need tuning in-game to feel right.
