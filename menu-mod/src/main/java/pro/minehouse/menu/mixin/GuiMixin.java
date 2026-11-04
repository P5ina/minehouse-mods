package pro.minehouse.menu.mixin;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pro.minehouse.menu.intro.Intro;
import pro.minehouse.menu.intro.IntroScreen;

/** Shows the intro instead of the title screen the first time it opens (once per intro version). */
@Mixin(Gui.class)
public abstract class GuiMixin {
	@Shadow
	public abstract void setScreen(Screen screen);

	@Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
	private void minehouse$intro(Screen screen, CallbackInfo ci) {
		if (screen instanceof TitleScreen) {
			Intro.Meta meta = Intro.takePending();
			if (meta != null) {
				ci.cancel();
				this.setScreen(new IntroScreen(screen, meta, false));
			}
		}
	}
}
