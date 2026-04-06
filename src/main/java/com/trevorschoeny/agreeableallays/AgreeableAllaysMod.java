package com.trevorschoeny.agreeableallays;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AgreeableAllaysMod implements ModInitializer {
    public static final String MOD_ID = "agreeable-allays";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        // Register custom memory types before any allay brain is built
        AgreeableAllaysMemory.register();

        LOGGER.info("[AgreeableAllays] Loaded successfully!");
    }
}
