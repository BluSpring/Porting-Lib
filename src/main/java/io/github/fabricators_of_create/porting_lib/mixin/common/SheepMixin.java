package io.github.fabricators_of_create.porting_lib.mixin.common;

import java.util.Map;

import io.github.fabricators_of_create.porting_lib.extensions.IShearable;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;

@Mixin(Sheep.class)
public abstract class SheepMixin extends Entity implements IShearable {

	@Shadow
	@Final
	private static Map<DyeColor, ItemLike> ITEM_BY_DYE;

	public SheepMixin(EntityType<?> entityType, Level level) {
		super(entityType, level);
	}

	@Shadow
	public abstract boolean readyForShearing();

	@Shadow
	public abstract void setSheared(boolean sheared);

	@Shadow
	public abstract DyeColor getColor();

	@Unique
	@Override
	public boolean isShearable(@NotNull ItemStack item, Level world, BlockPos pos) {
		return readyForShearing();
	}

	@NotNull
	@Override
	public java.util.List<ItemStack> onSheared(@Nullable Player player, @NotNull ItemStack item, Level world, BlockPos pos, int fortune) {
		world.playSound(null, (Sheep) (Object) this, SoundEvents.SHEEP_SHEAR, player == null ? SoundSource.BLOCKS : SoundSource.PLAYERS, 1.0F, 1.0F);
		if (!world.isClientSide) {
			this.setSheared(true);
			int i = 1 + this.random.nextInt(3);

			java.util.List<ItemStack> items = new java.util.ArrayList<>();
			for (int j = 0; j < i; ++j) {
				items.add(new ItemStack(ITEM_BY_DYE.get(this.getColor())));
			}
			return items;
		}
		return java.util.Collections.emptyList();
	}
}
