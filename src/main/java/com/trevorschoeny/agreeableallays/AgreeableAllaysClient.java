package com.trevorschoeny.agreeableallays;

import com.trevorschoeny.agreeableallays.access.SittingAllay;
import com.trevorschoeny.menukit.MKFamily;
import com.trevorschoeny.menukit.MKHudAnchor;
import com.trevorschoeny.menukit.MKHudPanel;
import com.trevorschoeny.menukit.MKPanel;
import com.trevorschoeny.menukit.MenuKit;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.animal.allay.Allay;

public class AgreeableAllaysClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Join the shared family
        MKFamily family = MenuKit.family("trevmods")
                .modId(AgreeableAllaysMod.MOD_ID);

        KeyMapping.Category category = family.getKeybindCategory();

        // HUD tooltip: shows "Shift + Right-click: Stay/Follow" when
        // the player is shifting and looking at a bonded allay.
        MKHudPanel.builder("allay_action_hint")
                .anchor(MKHudAnchor.CENTER, 0, 20)
                .padding(4).autoSize()
                .style(MKPanel.Style.NONE)
                .showWhen(AgreeableAllaysClient::isLookingAtBondedAllay)
                .text(0, 0, AgreeableAllaysClient::getActionHintText)
                .build();
    }

    /**
     * Returns true when the player is shifting and their crosshair is
     * on a bonded allay (determined by the allay holding an item).
     */
    private static boolean isLookingAtBondedAllay() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        if (!mc.player.isShiftKeyDown()) return false;
        if (!(mc.crosshairPickEntity instanceof Allay allay)) return false;
        // An allay holding an item is bonded (item = filter item given by player)
        return !allay.getItemInHand(InteractionHand.MAIN_HAND).isEmpty();
    }

    /**
     * Returns the action text based on the targeted allay's sitting state.
     */
    private static String getActionHintText() {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.crosshairPickEntity instanceof Allay allay)) return "";
        boolean sitting = ((SittingAllay) allay).agreeableallays$isSitting();
        return sitting ? "Shift + Right-click: Follow" : "Shift + Right-click: Stay";
    }
}
