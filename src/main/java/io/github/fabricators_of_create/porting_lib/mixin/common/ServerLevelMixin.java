package io.github.fabricators_of_create.porting_lib.mixin.common;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import io.github.fabricators_of_create.porting_lib.entity.PartEntity;
import io.github.fabricators_of_create.porting_lib.event.common.EntityEvents;
import io.github.fabricators_of_create.porting_lib.event.common.ExplosionEvents;

import io.github.fabricators_of_create.porting_lib.extensions.LevelExtensions;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;

import net.minecraft.server.level.ServerPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin implements LevelExtensions {
	@Inject(
			method = "explode",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/world/level/Explosion;explode()V",
					shift = At.Shift.BEFORE
			),
			locals = LocalCapture.CAPTURE_FAILHARD,
			cancellable = true
	)
	@SuppressWarnings("InvalidInjectorMethodSignature")
	public void port_lib$onStartExplosion(Entity exploder, DamageSource damageSource,
										ExplosionDamageCalculator context, double x,
										double y, double z, float size, boolean causesFire,
										Explosion.BlockInteraction mode, CallbackInfoReturnable<Explosion> cir,
										Explosion explosion) {
		if (ExplosionEvents.START.invoker().onExplosionStart((Level) (Object) this, explosion))
			cir.setReturnValue(explosion);
	}

	@ModifyReturnValue(method = "getEntityOrPart", at = @At("RETURN"))
	public Entity port_lib$getMultipart(Entity entity, int id) {
		if (entity == null) {
			Int2ObjectMap<PartEntity<?>> partEntityMap = getPartEntityMap();
			if (partEntityMap != null) {
				return partEntityMap.get(id);
			}
		}
		return entity;
	}

	@Inject(method = "addPlayer", at = @At("HEAD"), cancellable = true)
	public void port_lib$addEntityEvent(ServerPlayer serverPlayer, CallbackInfo ci) {
		if (EntityEvents.ON_JOIN_WORLD.invoker().onJoinWorld(serverPlayer, (Level) (Object) this, false))
			ci.cancel();
	}
}
