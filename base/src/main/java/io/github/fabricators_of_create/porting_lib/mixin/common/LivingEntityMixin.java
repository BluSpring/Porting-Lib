package io.github.fabricators_of_create.porting_lib.mixin.common;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import com.llamalad7.mixinextras.sugar.Local;

import io.github.fabricators_of_create.porting_lib.PortingLib;
import io.github.fabricators_of_create.porting_lib.item.ContinueUsingItem;
import io.github.fabricators_of_create.porting_lib.item.UsingTickItem;

import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootTable;

import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.At.Shift;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import io.github.fabricators_of_create.porting_lib.block.CustomFrictionBlock;
import io.github.fabricators_of_create.porting_lib.block.CustomLandingEffectsBlock;
import io.github.fabricators_of_create.porting_lib.event.common.LivingEntityEvents;
import io.github.fabricators_of_create.porting_lib.event.common.LivingEntityEvents.Fall.FallEvent;
import io.github.fabricators_of_create.porting_lib.event.common.PotionEvents;
import io.github.fabricators_of_create.porting_lib.event.common.LivingEntityUseItemEvents;
import io.github.fabricators_of_create.porting_lib.extensions.extensions.EntityExtensions;
import io.github.fabricators_of_create.porting_lib.item.EntitySwingListenerItem;
import io.github.fabricators_of_create.porting_lib.item.EquipmentItem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

@SuppressWarnings("ConstantConditions")
@Mixin(value = LivingEntity.class, priority = 500)
public abstract class LivingEntityMixin extends Entity implements EntityExtensions {
	@Shadow
	protected Player lastHurtByPlayer;

	@Shadow
	public abstract ItemStack getItemInHand(InteractionHand interactionHand);

	@Shadow
	public abstract ItemStack getUseItem();

	@Shadow
	public abstract int getUseItemRemainingTicks();

	@Shadow
	protected ItemStack useItem;

	@Shadow
	protected int useItemRemaining;

	@Shadow
	public abstract InteractionHand getUsedItemHand();

	@Shadow
	protected int lastHurtByPlayerTime;

	public LivingEntityMixin(EntityType<?> entityType, Level world) {
		super(entityType, world);
	}

	@ModifyArgs(
			method = "dropAllDeathLoot",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/world/entity/LivingEntity;dropCustomDeathLoot(Lnet/minecraft/world/damagesource/DamageSource;IZ)V"
			)
	)
	private void port_lib$modifyLootingLevel(Args args) {
		DamageSource source = args.get(0);
		int originalLevel = args.get(1);
		boolean recentlyHit = args.get(2);
		int modifiedLevel = LivingEntityEvents.LOOTING_LEVEL.invoker().modifyLootingLevel(source, (LivingEntity) (Object) this, originalLevel, recentlyHit);
		args.set(1, modifiedLevel);
	}

	@Inject(method = "dropAllDeathLoot", at = @At("HEAD"))
	private void port_lib$startCapturingDrops(DamageSource damageSource, CallbackInfo ci) {
		captureDrops(new ArrayList<>());
	}

	// Cry it's a redirect
	@Redirect(method = "dropFromLootTable", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/storage/loot/LootTable;getRandomItems(Lnet/minecraft/world/level/storage/loot/LootContext;Ljava/util/function/Consumer;)V"))
	private void useCustomLootMethod(LootTable instance, LootContext ctx, Consumer<ItemStack> lootConsumer, @Local(index = 4) LootTable lootTable) {
		lootTable.getRandomItems(ctx).forEach(this::spawnAtLocation);
	}

	private int port_lib$lootingLevel;

	@ModifyVariable(
			method = "dropAllDeathLoot",
			at = @At(
					value = "FIELD",
					target = "Lnet/minecraft/world/entity/LivingEntity;lastHurtByPlayerTime:I"
			)
	)
	private int port_lib$grabLootingLevel(int lootingLevel) {
		port_lib$lootingLevel = lootingLevel;
		return lootingLevel;
	}

	@Inject(method = "dropAllDeathLoot", at = @At("RETURN"))
	private void port_lib$dropCapturedDrops(DamageSource source, CallbackInfo ci) {
		Collection<ItemEntity> drops = this.captureDrops(null);
		boolean cancelled = LivingEntityEvents.DROPS.invoker().onLivingEntityDrops(
				(LivingEntity) (Object) this, source, drops, port_lib$lootingLevel, lastHurtByPlayerTime > 0
		);
		if (!cancelled)
			drops.forEach(e -> level.addFreshEntity(e));
	}

	@Unique
	private FallEvent port_lib$currentFallEvent = null;

	@Inject(method = "causeFallDamage", at = @At("HEAD"), cancellable = true)
	public void port_lib$cancelFall(float fallDistance, float multiplier, DamageSource source, CallbackInfoReturnable<Boolean> cir) {
		port_lib$currentFallEvent = new FallEvent((LivingEntity) (Object) this, source, fallDistance, multiplier);
		port_lib$currentFallEvent.sendEvent();
		if (port_lib$currentFallEvent.isCanceled()) {
			cir.setReturnValue(true);
		}
	}

	@ModifyVariable(method = "causeFallDamage", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	public float port_lib$modifyDistance(float fallDistance) {
		if (port_lib$currentFallEvent != null) {
			return port_lib$currentFallEvent.getDistance();
		}
		return fallDistance;
	}

	@ModifyVariable(method = "causeFallDamage", at = @At("HEAD"), argsOnly = true, ordinal = 1)
	public float port_lib$modifyMultiplier(float multiplier) {
		if (port_lib$currentFallEvent != null) {
			return port_lib$currentFallEvent.getDamageMultiplier();
		}
		return multiplier;
	}

	@Inject(method = "swing(Lnet/minecraft/world/InteractionHand;Z)V", at = @At("HEAD"), cancellable = true)
	private void port_lib$swingHand(InteractionHand hand, boolean bl, CallbackInfo ci) {
		ItemStack stack = getItemInHand(hand);
		if (!stack.isEmpty() && stack.getItem() instanceof EntitySwingListenerItem listener && listener.onEntitySwing(stack, (LivingEntity) (Object) this))
			ci.cancel();
	}

	@ModifyArgs(method = "dropExperience", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ExperienceOrb;award(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/phys/Vec3;I)V"))
	private void create$dropExperience(Args args) {
		int amount = args.get(2);
		int newAmount = LivingEntityEvents.EXPERIENCE_DROP.invoker().onLivingEntityExperienceDrop(amount, lastHurtByPlayer, (LivingEntity) (Object) this);
		if (amount != newAmount) args.set(2, newAmount);
	}

	@Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;tick()V"))
	private void port_lib$tick(CallbackInfo ci) {
		LivingEntityEvents.TICK.invoker().onLivingEntityTick((LivingEntity) (Object) this);
	}

	@ModifyVariable(method = "knockback", at = @At("STORE"), ordinal = 0, argsOnly = true)
	private double port_lib$takeKnockback(double f) {
		if (lastHurtByPlayer != null)
			return LivingEntityEvents.KNOCKBACK_STRENGTH.invoker().onLivingEntityTakeKnockback(f, lastHurtByPlayer);

		return f;
	}

	@ModifyVariable(method = "hurt", at = @At("HEAD"), argsOnly = true)
	private float port_lib$onHurt(float amount, DamageSource source, float amount2) {
		return LivingEntityEvents.HURT.invoker().onHurt(source, (LivingEntity) (Object) this, amount);
	}

	@Inject(method = "jumpFromGround", at = @At("TAIL"))
	public void port_lib$onJump(CallbackInfo ci) {
		LivingEntityEvents.JUMP.invoker().onLivingEntityJump((LivingEntity) (Object) this);
	}

	@SuppressWarnings("InvalidInjectorMethodSignature")
	@Inject(
			method = "checkFallDamage",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/server/level/ServerLevel;sendParticles(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I",
					shift = At.Shift.BEFORE
			),
			locals = LocalCapture.CAPTURE_FAILHARD,
			cancellable = true
	)
	protected void port_lib$updateFallState(double y, boolean onGround, BlockState state, BlockPos pos,
										  CallbackInfo ci, float f, double d, int i) {
		if (state.getBlock() instanceof CustomLandingEffectsBlock custom &&
				custom.addLandingEffects(state, (ServerLevel) level, pos, state, (LivingEntity) (Object) this, i)) {
			super.checkFallDamage(y, onGround, state, pos);
			ci.cancel();
		}
	}

	@SuppressWarnings("InvalidInjectorMethodSignature")
	@ModifyVariable(
			method = "travel",
			slice = @Slice(from = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getBlockPosBelowThatAffectsMyMovement()Lnet/minecraft/core/BlockPos;")),
			at = @At(value = "INVOKE_ASSIGN", target = "Lnet/minecraft/world/level/block/Block;getFriction()F")
	)
	public float port_lib$setSlipperiness(float p) {
		BlockPos pos = getBlockPosBelowThatAffectsMyMovement();
		BlockState state = level.getBlockState(pos);
		if (state.getBlock() instanceof CustomFrictionBlock custom) {
			return custom.getFriction(state, level, pos, (LivingEntity) (Object) this);
		}
		return p;
	}

	@Inject(method = "completeUsingItem", at = @At(value = "INVOKE", shift = Shift.BY, by = 2, target = "Lnet/minecraft/world/item/ItemStack;finishUsingItem(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/world/item/ItemStack;"),
			locals = LocalCapture.CAPTURE_FAILHARD)
	public void port_lib$onFinishUsing(CallbackInfo ci, InteractionHand hand, ItemStack result) {
		LivingEntityUseItemEvents.LIVING_USE_ITEM_FINISH.invoker().onUseItem((LivingEntity) (Object) this, this.getUseItem().copy(), getUseItemRemainingTicks(), result);
	}

	@Inject(method = "getEquipmentSlotForItem", at = @At("HEAD"), cancellable = true)
	private static void port_lib$getSlotForItemStack(ItemStack itemStack, CallbackInfoReturnable<EquipmentSlot> cir) {
		if (itemStack.getItem() instanceof EquipmentItem equipment) {
			cir.setReturnValue(equipment.getEquipmentSlot(itemStack));
		}
	}

	@Inject(
			method = "addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z",
			at = @At(
					value = "INVOKE",
					target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;",
					shift = Shift.BY,
					by = 3,
					remap = false
			),
			locals = LocalCapture.CAPTURE_FAILHARD
	)
	public void port_lib$addEffect(MobEffectInstance newEffect, @Nullable Entity source, CallbackInfoReturnable<Boolean> cir,
								   MobEffectInstance oldEffect) {
		PotionEvents.POTION_ADDED.invoker().onPotionAdded((LivingEntity) (Object) this, newEffect, oldEffect, source);
	}

	@Inject(method = "canBeAffected", at = @At("HEAD"), cancellable = true)
	public void port_lib$canBeAffected(MobEffectInstance effect, CallbackInfoReturnable<Boolean> cir) {
		InteractionResult result = PotionEvents.POTION_APPLICABLE.invoker().onPotionApplicable((LivingEntity) (Object) this, effect);
		if (result != InteractionResult.PASS)
			cir.setReturnValue(result == InteractionResult.SUCCESS);
	}

	@Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
	public void port_lib$attackEvent(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
		if(LivingEntityEvents.ATTACK.invoker().onAttack((LivingEntity) (Object) this, source, amount)) cir.setReturnValue(false);
	}

	@Inject(method = "updatingUsingItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getItemInHand(Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/item/ItemStack;", shift = Shift.AFTER, ordinal = 1))
	public void port_lib$onUsingTick(CallbackInfo ci) {
		if (useItem.getItem() instanceof UsingTickItem usingTickItem) {
			if (!this.useItem.isEmpty()) {
				if (useItemRemaining > 0)
					usingTickItem.onUsingTick(useItem, (LivingEntity) (Object) this, useItemRemaining);
			}
		}
	}

	@ModifyExpressionValue(method = "updatingUsingItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;isSame(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z"))
	public boolean port_lib$canContinueUsing(boolean original) {
		if (useItem.getItem() instanceof ContinueUsingItem continueUsingItem) {
			ItemStack to = this.getItemInHand(this.getUsedItemHand());
			if (!useItem.isEmpty() && !to.isEmpty())
			{
				return continueUsingItem.canContinueUsing(useItem, to);
			}
			return false;
		}
		return original;
	}
}
