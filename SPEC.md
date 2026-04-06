# Agreeable Allays — Feature Spec

## North Star

Make allays feel like loyal companions — like a Pokémon at your side — instead of chaotic item-throwing gnats that block your view and abandon you the moment you take their item.

---

## Vanilla Allay Behavior (What We're Changing)

Vanilla allays use the Brain AI system with `LIKED_PLAYER` memory. When given an item:
- They follow the player, pick up matching items off the ground, fly to the player's **face**, and throw items at them
- They pathfind with `FlyingMoveControl` (no teleport mechanic)
- If the player moves >64 blocks away, the allay **forgets them entirely**
- Taking the item back clears `LIKED_PLAYER` immediately — the allay drifts away with no memory of you
- Note blocks redirect item delivery (allay drops items at the note block instead of the player, lasts 30s after note block plays)

### Problems
1. Flies directly into your face to deliver items — blocks your view
2. Wanders far away when you take the item back
3. Can't keep up with elytra/fast movement — no teleport, just pathfinding
4. 64-block distance = instant amnesia

---

## Agreeable Behavior (What We're Building)

### 1. Companion Positioning

**A bonded allay stays by the player at all times, only leaving to fetch items and bring them back.**

- Default position is to the side and slightly behind the player, not in front — never blocking your view
- It should drift into the edge of your peripheral vision occasionally — enough to feel present, not enough to obstruct
- When idle (not working), it has subtle, infrequent idle animations
- The allay only leaves the player's side to go pick up a matching item, then returns to the player's side before delivering
- Multiple allays cluster loosely — they don't need to maintain strict formation, just don't stack on the same point. They stay nearby but not rigidly close

### 2. Teleportation

**Wolf-style teleport when the player gets too far away.**

- If the companion allay is beyond 64 blocks, it teleports to the player's side (same range as vanilla's amnesia threshold, but teleport instead of forget)
- This gives allays room to travel far for item pickups before snapping back
- The 64-block amnesia is removed for companion allays — they stay bonded as long as they hold an item

### 3. Item Delivery

**Items go directly into your inventory — once the allay returns to you.**

- The allay leaves your side to pick up a matching item, then flies back to you
- Once it's back within close range, it silently slots the item into your inventory
- If inventory is full, the item drops on the ground (standard full-inventory behavior)
- No more "fly to your face and throw" — delivery happens automatically when the allay is close

### 4. Note Block Behavior

**Preserved as-is from vanilla.**

- Allays still hear note blocks and redirect delivery to them
- 30-second timer, resets on replay
- This is useful for automation and doesn't conflict with companion behavior

### 5. Non-Collidable With Owner

**Allays have zero collision with all players.**

- Allays no longer collide with or push any player (not just their owner)
- Vanilla already prevents liked-player damage; this extends to removing all physical interaction
- Allays still collide with blocks and other entities normally

### 6. Graceful Detach (10-Second Linger)

**Taking an allay's item doesn't cause instant abandonment.**

- When you take the item back, the allay lingers where you left it for 10 seconds
- During those 10 seconds it remains nearby but does not follow
- After 10 seconds, `LIKED_PLAYER` clears and it becomes a wild allay again
- If you give it a new item during the 10-second window, it re-bonds immediately

### 7. Sit / Stay

**Tell a companion allay to stay put.**

- Shift-right-click toggles sit/stay on companion allays only (must be bonded)
- A sitting allay:
  - Stays at its position, does not follow
  - Still picks up matching items that land within reach (passive collection)
  - Does NOT move to pick up distant items
  - Does NOT deliver to note blocks
  - Remains a companion (bond is not broken)
- Shift-right-click again to un-sit
- Sitting state persists across logout/login

**Emergent use case**: Sit an allay holding diamonds near your mine → it passively collects any diamonds that land nearby → you come back and pick it up later. A little collection bin with wings.

---

## Interaction Model

| Action | Target | Result |
|--------|--------|--------|
| Right-click with item | Empty allay | Gives item, allay becomes your companion |
| Right-click empty hand | Companion allay | Takes item back (goes to hand), 10-second graceful detach begins |
| Right-click with item | Companion allay | **Swaps** items — allay gets new item, old item goes to your inventory |
| Shift-right-click | Companion allay | Toggles sit/stay |

### Interaction Edge Cases

- **Swap when inventory is full**: Old item drops on the ground
- **Taking item from a sitting allay**: 10-second detach begins. After 10 seconds, the allay unbonds AND un-sits, becoming a wild wandering allay again
- **Give item during 10-second linger**: Re-bonds immediately, linger timer cancels, allay returns to companion behavior
- **Shift-right-click on a wild allay**: Nothing happens (must be bonded to accept sit/stay commands)

---

## Persistence

- **Companion bond** (`LIKED_PLAYER`): Persisted in entity NBT (vanilla already does this)
- **Sit/stay state**: New boolean in entity NBT, must be added
- **10-second detach timer**: Not persisted — if you log out during the 10-second window, the allay goes wild immediately (acceptable)

---

## Sound Cues

New subtle sounds for:
- Item swap (distinct from the normal "give item" sound)
- Sit command
- Un-sit command
- Possibly: a soft chime when the allay teleports to you (so you know it caught up)

---

## Out of Scope (For Now)

- **Formation positioning** for multiple allays (just cluster loosely)
- **Cross-dimension following** (vanilla portal behavior is fine)
- **Death protection / respawn** (allays just die normally)
- **Speed scaling / velocity matching** (teleportation handles the gap)
- **Visual attachment indicators** beyond the held item (the item in hand is enough)
- **Custom allay skins or colors**

---

## Technical Approach (High-Level)

The allay Brain AI system is mixin-friendly — behaviors are modular and can be individually replaced:

1. **Replace `GoAndGiveItemsToTarget`** — new behavior that positions the allay to the player's side/behind and directly inserts items into inventory
2. **Add teleport logic** — mixin or new behavior that teleports when distance exceeds threshold (similar to `FollowOwnerGoal` in wolf/cat AI, but adapted for Brain system)
3. **Modify `mobInteract()`** — handle swap interaction and shift-click for sit/stay
4. **Add collision exclusion** — mixin to skip collision between allay and its liked player
5. **Add detach timer** — new memory type or entity field for the 10-second grace period
6. **Add sit state** — new entity data field, persisted in NBT, checked by movement behaviors
7. **Idle positioning behavior** — new behavior for peripheral drift and subtle idle animations
