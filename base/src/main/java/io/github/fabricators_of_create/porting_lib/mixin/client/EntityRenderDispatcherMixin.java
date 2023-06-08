package io.github.fabricators_of_create.porting_lib.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import io.github.fabricators_of_create.porting_lib.entity.MultiPartEntity;
import io.github.fabricators_of_create.porting_lib.entity.PartEntity;
import io.github.fabricators_of_create.porting_lib.event.client.EntityAddedLayerCallback;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;

import net.minecraft.world.entity.EntityType;

import net.minecraft.world.entity.player.Player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.util.Map;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
	@Shadow
	private Map<EntityType<?>, EntityRenderer<?>> renderers;

	@Shadow
	private Map<String, EntityRenderer<? extends Player>> playerRenderers;

	@Inject(method = "renderHitbox", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLineBox(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/phys/AABB;FFFF)V", ordinal = 0, shift = At.Shift.AFTER))
	private static void port_lib$renderMultipartHitboxes(PoseStack pMatrixStack, VertexConsumer pBuffer, Entity entity, float pPartialTicks, CallbackInfo ci) {
		if (entity instanceof MultiPartEntity pEntity && pEntity.isMultipartEntity()) {
			double d0 = -Mth.lerp(pPartialTicks, entity.xOld, entity.getX());
			double d1 = -Mth.lerp(pPartialTicks, entity.yOld, entity.getY());
			double d2 = -Mth.lerp(pPartialTicks, entity.zOld, entity.getZ());

			for(PartEntity<?> enderdragonpart : pEntity.getParts()) {
				pMatrixStack.pushPose();
				double d3 = d0 + Mth.lerp(pPartialTicks, enderdragonpart.xOld, enderdragonpart.getX());
				double d4 = d1 + Mth.lerp(pPartialTicks, enderdragonpart.yOld, enderdragonpart.getY());
				double d5 = d2 + Mth.lerp(pPartialTicks, enderdragonpart.zOld, enderdragonpart.getZ());
				pMatrixStack.translate(d3, d4, d5);
				LevelRenderer.renderLineBox(pMatrixStack, pBuffer, enderdragonpart.getBoundingBox().move(-enderdragonpart.getX(), -enderdragonpart.getY(), -enderdragonpart.getZ()), 0.25F, 1.0F, 0.0F, 1.0F);
				pMatrixStack.popPose();
			}
		}
	}

	@Inject(method = "onResourceManagerReload", at = @At("TAIL"), locals = LocalCapture.CAPTURE_FAILHARD)
	public void port_lib$resourceReload(ResourceManager resourceManager, CallbackInfo ci, EntityRendererProvider.Context context) {
		EntityAddedLayerCallback.EVENT.invoker().addLayers(renderers, playerRenderers);
	}
}
