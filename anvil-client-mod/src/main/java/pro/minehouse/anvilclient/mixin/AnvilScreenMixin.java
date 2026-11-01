package pro.minehouse.anvilclient.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.gui.screens.inventory.ItemCombinerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.resources.Identifier;

import pro.minehouse.anvilclient.AnvilCost;

/**
 * Shows the real cost in the "Enchantment Cost: N" line instead of the 39 the server puts in the
 * data slot, with no "Too Expensive!" (the server has no such limit) and red when the player
 * doesn't have enough levels for the real cost.
 */
@Mixin(AnvilScreen.class)
abstract class AnvilScreenMixin extends ItemCombinerScreen<AnvilMenu> {
	@Shadow
	@Final
	private Player player;

	/** The real cost while it is drawn, -1 when the vanilla number is used. */
	@Unique
	private int minehouse$real = -1;

	private AnvilScreenMixin(AnvilMenu menu, Inventory inventory, Component title, Identifier menuResource) {
		super(menu, inventory, title, menuResource);
	}

	@ModifyExpressionValue(method = "extractLabels", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/inventory/AnvilMenu;getCost()I"))
	private int minehouse$realCost(int slotCost) {
		this.minehouse$real = AnvilCost.real(this.menu.containerId, slotCost);
		return this.minehouse$real >= 0 ? this.minehouse$real : slotCost;
	}

	@ModifyConstant(method = "extractLabels", constant = @Constant(intValue = 40))
	private int minehouse$noTooExpensive(int limit) {
		return this.minehouse$real >= 0 ? Integer.MAX_VALUE : limit;
	}

	@ModifyExpressionValue(method = "extractLabels", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/inventory/Slot;mayPickup(Lnet/minecraft/world/entity/player/Player;)Z"))
	private boolean minehouse$affordable(boolean original) {
		if (this.minehouse$real < 0) {
			return original;
		}

		return this.player.hasInfiniteMaterials() || this.player.experienceLevel >= this.minehouse$real;
	}
}
