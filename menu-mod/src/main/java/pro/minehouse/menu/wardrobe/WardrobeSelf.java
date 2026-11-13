package pro.minehouse.menu.wardrobe;

import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Your own cosmetics in third person and in the inventory. The server dresses you up only in the
 * packets other players get (your client draws you from your own inventory), so the menu mod puts
 * what you wear onto your own render state: the hat in the helmet's place, the backpack as a layer
 * placed like the server's item display (at the neck, following the body).
 */
public final class WardrobeSelf {
	static final RenderStateDataKey<ItemStackRenderState> BACKPACK = RenderStateDataKey.create(() -> "minehouse:backpack");

	/** Set while the wardrobe builds its preview, which dresses the player itself. */
	static boolean suppress;

	private WardrobeSelf() {
	}

	static void init() {
		LivingEntityRenderLayerRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
			if (renderer instanceof AvatarRenderer<?> avatar) {
				helper.register(new BackpackLayer(avatar));
			}
		});
	}

	/**
	 * The server's backpack for other players: an item display with one of our models riding them.
	 * Drawn by the layer instead, glued to the body (the display itself turns with the body yaw the
	 * server sends, which lags behind the walking animation, so it slid off to the side).
	 */
	public static boolean isCosmeticDisplay(Entity entity) {
		if (!(entity instanceof Display.ItemDisplay display) || !(entity.getVehicle() instanceof Avatar)) {
			return false;
		}
		Display.ItemDisplay.ItemRenderState item = display.itemRenderState();
		if (item == null) {
			return false;
		}
		Identifier model = item.itemStack().get(DataComponents.ITEM_MODEL);
		return model != null && WardrobeNet.NS.equals(model.getNamespace());
	}

	/** Called at the end of AvatarRenderer.extractRenderState. */
	public static void extract(Avatar avatar, AvatarRenderState state) {
		state.setData(BACKPACK, null);
		Minecraft mc = Minecraft.getInstance();
		if (suppress || mc.player == null) {
			return;
		}
		if (avatar != mc.player) {
			if (!visible(state)) {
				return;
			}
			for (Entity passenger : avatar.getPassengers()) {
				if (isCosmeticDisplay(passenger)) {
					ItemStackRenderState model = new ItemStackRenderState();
					mc.getItemModelResolver().updateForTopItem(model, ((Display.ItemDisplay) passenger).itemRenderState().itemStack(),
							ItemDisplayContext.HEAD, mc.level, null, 0);
					state.setData(BACKPACK, model);
					return;
				}
			}
			return;
		}
		WardrobeNet.State net = WardrobeNet.state;
		if (net == null) {
			return;
		}
		Map<String, String> worn = net.worn();
		if (mc.gui.screen() instanceof WardrobeScreen screen) {
			// In the fitting room: what you try on, turned by dragging, standing still.
			worn = screen.trying();
			state.bodyRot = avatar.getYRot() + screen.previewYaw();
			state.yRot = 0;
			state.xRot = 0;
			state.walkAnimationSpeed = 0;
		}
		String hat = worn.get("hat");
		if (hat != null) {
			ItemStack stack = WardrobeScreen.stack(hat);
			mc.getItemModelResolver().updateForTopItem(state.headItem, stack, ItemDisplayContext.HEAD, mc.level, null, 0);
			state.wornHeadType = null;
			state.headEquipment = stack;
		}
		String back = worn.get("backpack");
		if (back != null && visible(state)) {
			ItemStackRenderState model = new ItemStackRenderState();
			mc.getItemModelResolver().updateForTopItem(model, WardrobeScreen.stack(back), ItemDisplayContext.HEAD, mc.level, null, 0);
			state.setData(BACKPACK, model);
		}
	}

	/** Like the server: no backpack while swimming, gliding, sleeping or invisible. */
	private static boolean visible(AvatarRenderState state) {
		return !state.isInvisible && !state.hasPose(Pose.SWIMMING) && !state.hasPose(Pose.FALL_FLYING) && !state.hasPose(Pose.SLEEPING);
	}

	static final class BackpackLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
		BackpackLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
			super(parent);
		}

		@Override
		public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, AvatarRenderState state, float yRot, float xRot) {
			ItemStackRenderState model = state.getData(BACKPACK);
			if (model == null || model.isEmpty()) {
				return;
			}
			poseStack.pushPose();
			// The body's pivot is the neck, like the head's: CustomHeadLayer.translateToHead, but
			// turning and leaning with the body instead of the head.
			getParentModel().body.translateAndRotate(poseStack);
			poseStack.translate(0, -0.25f, 0);
			poseStack.rotateDegrees(Axis.YP, 180);
			poseStack.scale(0.625f, -0.625f, -0.625f);
			model.submit(poseStack, collector, light, OverlayTexture.NO_OVERLAY, state.outlineColor);
			poseStack.popPose();
		}
	}
}
