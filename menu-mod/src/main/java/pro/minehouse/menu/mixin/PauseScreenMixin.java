package pro.minehouse.menu.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * «Меню сервера» in the pause screen, a full-width row under Advancements/Stats. It runs
 * {@code /menu}, which opens the server's menu window (MineHouse Guides on the server). Shown
 * only where the server has that menu: on a multiplayer server whose commands include both
 * {@code menu} and {@code guide}, so other servers and singleplayer keep the vanilla screen.
 */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {
	protected PauseScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "createPauseMenu", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/PauseScreen;getCustomAdditions()Ljava/util/Optional;"))
	private void minehouse$serverMenu(CallbackInfo ci, @Local GridLayout.RowHelper rows) {
		ClientPacketListener connection = this.minecraft.getConnection();
		if (connection == null || this.minecraft.isLocalServer()) return;
		var root = connection.getCommands().getRoot();
		if (root.getChild("menu") == null || root.getChild("guide") == null) return;
		rows.addChild(Button.builder(Component.literal("Меню сервера"), b -> {
			ClientPacketListener now = this.minecraft.getConnection();
			if (now != null) now.sendCommand("menu");
		}).width(204).build(), 2);
	}
}
