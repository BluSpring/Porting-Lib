package io.github.fabricators_of_create.porting_lib.transfer.item;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.item.base.SingleStackStorage;
import net.minecraft.world.item.ItemStack;

public class ItemStackHandlerSlot extends SingleStackStorage {
	private final int index;
	private final ItemStackHandler handler;
	private ItemStack stack = ItemStack.EMPTY;
	private ItemStack lastStack; // last stack pre-transaction
	private ItemVariant variant = ItemVariant.blank();

	public ItemStackHandlerSlot(int index, ItemStackHandler handler, ItemStack initial) {
		this.index = index;
		this.handler = handler;
		this.lastStack = initial;
		this.setStack(initial);
		handler.initSlot(this);
	}

	@Override
	public boolean canInsert(ItemVariant itemVariant) {
		return handler.isItemValid(index, itemVariant);
	}

	@Override
	public int getCapacity(ItemVariant itemVariant) {
		return handler.getStackLimit(index, itemVariant);
	}

	@Override
	public ItemStack getStack() {
		return stack;
	}

	@Override // for transactions
	protected void setStack(ItemStack stack) {
		this.stack = stack;
		this.variant = ItemVariant.of(stack);
	}

	// for manual setting
	public void setNewStack(ItemStack stack) {
		setStack(stack);
		onFinalCommit();
	}

	@Override
	public ItemVariant getResource() {
		return variant;
	}

	public int getIndex() {
		return index;
	}

	@Override
	protected void onFinalCommit() {
		handler.onStackChange(this, lastStack, stack);
		this.lastStack = stack;
		handler.onContentsChanged(index);
	}
}
