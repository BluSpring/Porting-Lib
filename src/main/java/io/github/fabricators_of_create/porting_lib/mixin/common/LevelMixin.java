package io.github.fabricators_of_create.porting_lib.mixin.common;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Predicate;

import io.github.fabricators_of_create.porting_lib.PortingLib;
import io.github.fabricators_of_create.porting_lib.block.LightEmissiveBlock;
import io.github.fabricators_of_create.porting_lib.entity.PartEntity;
import io.github.fabricators_of_create.porting_lib.event.common.ExplosionEvents;
import io.github.fabricators_of_create.porting_lib.extensions.BlockEntityExtensions;
import io.github.fabricators_of_create.porting_lib.extensions.LevelExtensions;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.EntityTypeTest;

import net.minecraft.world.phys.AABB;

import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.At.Shift;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import io.github.fabricators_of_create.porting_lib.block.NeighborChangeListeningBlock;
import io.github.fabricators_of_create.porting_lib.block.WeakPowerCheckingBlock;
import io.github.fabricators_of_create.porting_lib.util.MixinHelper;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

@Mixin(value = Level.class, priority = 1100) // need to apply after lithium
public abstract class LevelMixin implements LevelAccessor, LevelExtensions {
	// only non-null during transactions. Is set back to null in
	// onFinalCommit on commits, and through snapshot rollbacks on aborts.
	@Unique
	private List<ChangedPosData> port_lib$modifiedStates = null;
	@Unique
	private final ArrayList<BlockEntity> port_lib$freshBlockEntities = new ArrayList<>();
	@Unique
	private final ArrayList<BlockEntity> port_lib$pendingFreshBlockEntities = new ArrayList<>();

	@Unique
	private final SnapshotParticipant<LevelSnapshotData> port_lib$snapshotParticipant = new SnapshotParticipant<>() {

		@Override
		protected LevelSnapshotData createSnapshot() {
			LevelSnapshotData data = new LevelSnapshotData(port_lib$modifiedStates);
			if (port_lib$modifiedStates == null) port_lib$modifiedStates = new LinkedList<>();
			return data;
		}

		@Override
		protected void readSnapshot(LevelSnapshotData snapshot) {
			port_lib$modifiedStates = snapshot.changedStates();
		}

		@Override
		protected void onFinalCommit() {
			super.onFinalCommit();
			List<ChangedPosData> modifications = port_lib$modifiedStates;
			port_lib$modifiedStates = null;
			for (ChangedPosData data : modifications) {
				setBlock(data.pos(), data.state(), data.flags());
			}
		}
	};

	@Shadow
	public abstract BlockState getBlockState(BlockPos blockPos);

	@Shadow
	private boolean tickingBlockEntities;

	@Shadow
	public abstract ProfilerFiller getProfiler();

	@Override
	public SnapshotParticipant<LevelSnapshotData> snapshotParticipant() {
		return port_lib$snapshotParticipant;
	}

	@Inject(method = "getBlockState", at = @At(value = "INVOKE", shift = Shift.BEFORE,
			target = "Lnet/minecraft/world/level/Level;getChunk(II)Lnet/minecraft/world/level/chunk/LevelChunk;"), cancellable = true)
	private void port_lib$getBlockState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
		if (port_lib$modifiedStates != null) {
			// iterate in reverse order - latest changes priority
			for (ChangedPosData data : port_lib$modifiedStates) {
				if (data.pos().equals(pos)) {
					BlockState state = data.state();
					if (state == null) {
						PortingLib.LOGGER.error("null blockstate stored in snapshots at " + pos);
						new Throwable().printStackTrace();
					} else {
						cir.setReturnValue(state);
					}
					return;
				}
			}
		}
	}

	@Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
			at = @At(value = "INVOKE", shift = Shift.BEFORE, target = "Lnet/minecraft/world/level/Level;getChunkAt(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/chunk/LevelChunk;"), cancellable = true)
	private void port_lib$setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft, CallbackInfoReturnable<Boolean> cir) {
		if (state == null) {
			PortingLib.LOGGER.error("Setting null blockstate at " + pos);
			new Throwable().printStackTrace();
		}
		if (port_lib$modifiedStates != null) {
			port_lib$modifiedStates.add(new ChangedPosData(pos, state, flags));
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At(value = "JUMP", opcode = Opcodes.IF_ACMPEQ), locals = LocalCapture.CAPTURE_FAILHARD)
	public void port_lib$lightBlock(BlockPos pos, BlockState state, int flags, int recursionLeft, CallbackInfoReturnable<Boolean> cir, LevelChunk levelChunk, Block block, BlockState blockstate, BlockState blockstate1) {
		BlockState old = getBlockState(pos);
		if (old.getBlock() instanceof LightEmissiveBlock lightEmissiveBlock) {

			int oldLight = lightEmissiveBlock.getLightEmission(old, this, pos);
			int oldOpacity = old.getLightBlock(this, pos);
			if (blockstate1 != blockstate && (blockstate1.getLightBlock(this, pos) != oldOpacity || (blockstate1.getBlock() instanceof LightEmissiveBlock lm ? lm.getLightEmission(blockstate1, this, pos) : blockstate1.getLightEmission()) != oldLight || blockstate1.useShapeForLightOcclusion() || blockstate.useShapeForLightOcclusion())) {
				this.getProfiler().push("queueCheckLight");
				this.getChunkSource().getLightEngine().checkBlock(pos);
				this.getProfiler().pop();
			}
		}
	}

	@Inject(method = "getSignal", at = @At("RETURN"), cancellable = true)
	public void port_lib$getRedstoneSignal(BlockPos blockPos, Direction direction, CallbackInfoReturnable<Integer> cir) {
		BlockState port_lib$blockstate = MixinHelper.<Level>cast(this).getBlockState(blockPos);
		int port_lib$i = port_lib$blockstate.getSignal(MixinHelper.<Level>cast(this), blockPos, direction);

		if (port_lib$blockstate.getBlock() instanceof WeakPowerCheckingBlock) {
			cir.setReturnValue(
					((WeakPowerCheckingBlock) port_lib$blockstate.getBlock()).shouldCheckWeakPower(port_lib$blockstate, MixinHelper.<Level>cast(this), blockPos, direction)
							? Math.max(port_lib$i, MixinHelper.<Level>cast(this).getDirectSignalTo(blockPos))
							: port_lib$i);
		}
	}

	@Inject(
			method = "updateNeighbourForOutputSignal",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
					shift = Shift.BY,
					by = 2,
					ordinal = 0
			),
			locals = LocalCapture.CAPTURE_FAILHARD
	)
	public void port_lib$updateNeighbourForOutputSignal(BlockPos pos, Block block, CallbackInfo ci,
														Iterator<?> var3, Direction direction, BlockPos offset,
														BlockState state) {
		if (state.getBlock() instanceof NeighborChangeListeningBlock listener) {
			listener.onNeighborChange(state, this, offset, pos);
		}
	}

	@Inject(
			method = "explode(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/damagesource/DamageSource;Lnet/minecraft/world/level/ExplosionDamageCalculator;DDDFZLnet/minecraft/world/level/Explosion$BlockInteraction;)Lnet/minecraft/world/level/Explosion;",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/world/level/Explosion;explode()V",
					shift = At.Shift.BEFORE
			),
			locals = LocalCapture.CAPTURE_FAILHARD
	)
	@SuppressWarnings("ALL")
	public void port_lib$onStartExplosion(@Nullable Entity exploder, @Nullable DamageSource damageSource, @Nullable ExplosionDamageCalculator context, double x, double y, double z, float size, boolean causesFire, Explosion.BlockInteraction mode, CallbackInfoReturnable<Explosion> cir, Explosion explosion) {
		if (ExplosionEvents.START.invoker().onExplosionStart((Level) (Object) this, explosion)) cir.setReturnValue(explosion);
	}

	// --- adding part entities to getEntities methods ---
	// inject to tail, capturing the found list of entities.
	// iterate over them, check if multiparts, add subentities

	@Inject(
			method = "getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;",
			at = @At("TAIL"),
			locals = LocalCapture.CAPTURE_FAILHARD
	)
	private void port_lib$appendPartEntitiesPredicate(@Nullable Entity entity, AABB area, Predicate<? super Entity> predicate, CallbackInfoReturnable<List<Entity>> cir, List<Entity> list) {
		try {
			for (PartEntity<?> p : this.getPartEntities()) {
				if (p != entity && p.getBoundingBox().intersects(area) && predicate.test(p)) {
					list.add(p);
				}
			}
		} catch (ClassCastException e) {
			System.out.println("h");
		}
	}

	@Inject(
			method = "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;",
			at = @At("TAIL"),
			locals = LocalCapture.CAPTURE_FAILHARD
	)
	private <T extends Entity> void port_lib$appendPartEntitiesTypeTest(EntityTypeTest<Entity, T> test, AABB area, Predicate<? super T> predicate, CallbackInfoReturnable<List<T>> cir, List<Entity> list) {
		try {
			for (PartEntity<?> p : this.getPartEntities()) {
				T t = test.tryCast(p);
				if (t != null && t.getBoundingBox().intersects(area) && predicate.test(t)) {
					list.add(t);
				}
			}
		} catch (ClassCastException ex) {
			System.out.println("h");
		}
	}

	@Inject(method = "tickBlockEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/profiling/ProfilerFiller;push(Ljava/lang/String;)V", shift = Shift.AFTER))
	public void port_lib$pendingBlockEntities(CallbackInfo ci) {
		if (!this.port_lib$pendingFreshBlockEntities.isEmpty()) {
			this.port_lib$freshBlockEntities.addAll(this.port_lib$pendingFreshBlockEntities);
			this.port_lib$pendingFreshBlockEntities.clear();
		}
	}

	@Inject(method = "tickBlockEntities", at = @At(value = "INVOKE", target = "Ljava/util/List;isEmpty()Z"))
	public void port_lib$onBlockEntitiesLoad(CallbackInfo ci) {
		if (!this.port_lib$freshBlockEntities.isEmpty()) {
			this.port_lib$freshBlockEntities.forEach(BlockEntityExtensions::onLoad);
			this.port_lib$freshBlockEntities.clear();
		}
	}

	@Unique
	@Override
	public void addFreshBlockEntities(Collection<BlockEntity> beList) {
		if (this.tickingBlockEntities) {
			this.port_lib$pendingFreshBlockEntities.addAll(beList);
		} else {
			this.port_lib$freshBlockEntities.addAll(beList);
		}
	}

}
