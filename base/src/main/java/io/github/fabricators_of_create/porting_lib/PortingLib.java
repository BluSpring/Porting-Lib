package io.github.fabricators_of_create.porting_lib;

import io.github.fabricators_of_create.porting_lib.util.UsernameCache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.fabricators_of_create.porting_lib.crafting.CraftingHelper;
import io.github.fabricators_of_create.porting_lib.data.ConditionalRecipe;
import io.github.fabricators_of_create.porting_lib.loot.CanToolPerformAction;
import io.github.fabricators_of_create.porting_lib.transfer.item.ItemItemStorages;
import io.github.fabricators_of_create.porting_lib.util.PortingHooks;
import io.github.fabricators_of_create.porting_lib.util.ServerLifecycleHooks;
import io.github.fabricators_of_create.porting_lib.util.TierSortingRegistry;
import io.github.fabricators_of_create.porting_lib.util.TrueCondition;
import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;

public class PortingLib implements ModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("Porting Lib Base");
	@Override
	public void onInitialize() {
		ServerLifecycleHooks.init();
		TierSortingRegistry.init();
		ConditionalRecipe.init();
		ItemItemStorages.init();
		CraftingHelper.init();
		TrueCondition.init();
		UsernameCache.load();
		PortingHooks.init();
		// can be used to force all mixins to apply
		// MixinEnvironment.getCurrentEnvironment().audit();

		Registry.register(Registry.LOOT_CONDITION_TYPE, new ResourceLocation("forge:can_tool_perform_action"), CanToolPerformAction.LOOT_CONDITION_TYPE);
	}
}
