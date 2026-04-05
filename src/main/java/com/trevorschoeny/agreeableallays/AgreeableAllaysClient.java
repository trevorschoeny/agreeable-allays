package com.trevorschoeny.agreeableallays;

import com.trevorschoeny.menukit.MKFamily;
import com.trevorschoeny.menukit.MenuKit;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.KeyMapping;

public class AgreeableAllaysClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Join the shared family
        MKFamily family = MenuKit.family("trevmods")
                .modId(AgreeableAllaysMod.MOD_ID);

        KeyMapping.Category category = family.getKeybindCategory();

        // TODO: register keybinds, config categories, etc.
    }
}
