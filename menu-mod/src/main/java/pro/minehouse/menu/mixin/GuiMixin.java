package pro.minehouse.menu.mixin;

import java.util.List;
import java.util.function.Function;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pro.minehouse.menu.intro.Intro;
import pro.minehouse.menu.intro.IntroScreen;

/**
 * Adds the intro as the last of the game's initial screens (once per intro version), so it plays
 * before the title screen and also before quick play: the MineHouse launcher starts the game with
 * --quickPlayMultiplayer, which skips the title screen.
 */
@Mixin(Gui.class)
public abstract class GuiMixin {
	@Inject(method = "addInitialScreens", at = @At("RETURN"))
	private void minehouse$intro(List<Function<Runnable, Screen>> screens, CallbackInfoReturnable<Boolean> cir) {
		Intro.Meta meta = Intro.takePending();
		if (meta != null) {
			screens.add(next -> new IntroScreen(next, meta, false));
		}
	}
}
