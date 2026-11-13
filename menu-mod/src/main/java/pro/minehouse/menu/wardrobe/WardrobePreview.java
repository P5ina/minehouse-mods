package pro.minehouse.menu.wardrobe;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Draws the wardrobe's 3D views into the GUI: the player with the cosmetics on (the backpack is
 * placed exactly like the server puts it: on the body, where a helmet would be), or a single
 * cosmetic turning on a card.
 */
public final class WardrobePreview extends PictureInPictureRenderer<WardrobePreview.State> {
	private static final int FULL_BRIGHT = 15728880;

	/**
	 * @param player   the player to draw, or null for an item alone
	 * @param bodyRot  the player's body yaw, degrees (as in the render state)
	 * @param back     a backpack on the player, or the item alone
	 * @param spin     item alone: yaw, degrees
	 */
	public record State(@Nullable EntityRenderState player, float bodyRot, @Nullable ItemStackRenderState back, float spin,
			int x0, int y0, int x1, int y1, float scale, @Nullable ScreenRectangle scissorArea, @Nullable ScreenRectangle bounds)
			implements PictureInPictureRenderState {
		public State(@Nullable EntityRenderState player, float bodyRot, @Nullable ItemStackRenderState back, float spin,
				int x0, int y0, int x1, int y1, float scale, @Nullable ScreenRectangle scissorArea) {
			this(player, bodyRot, back, spin, x0, y0, x1, y1, scale, scissorArea, PictureInPictureRenderState.getBounds(x0, y0, x1, y1, scissorArea));
		}
	}

	@Override
	public Class<State> getRenderStateClass() {
		return State.class;
	}

	@Override
	protected void renderToTexture(State state, PoseStack poseStack, SubmitNodeCollector collector) {
		Minecraft mc = Minecraft.getInstance();
		mc.gameRenderer.lighting().setupFor(Lighting.Entry.ENTITY_IN_UI);
		if (state.player() == null) {
			item(state, poseStack, collector);
			return;
		}

		EntityRenderState player = state.player();
		// The same as GuiEntityRenderer: feet at the bottom, upside down because GUI y goes down.
		poseStack.translate(0, player.boundingBoxHeight / 2f + 0.0625f, 0);
		poseStack.rotate(Axis.ZP, (float) Math.PI);
		poseStack.rotateDegrees(Axis.XP, -8);
		mc.getEntityRenderDispatcher().submit(player, new CameraRenderState(), 0, 0, 0, poseStack, collector);

		if (state.back() != null && !state.back().isEmpty()) {
			// LivingEntityRenderer + AvatarRenderer, then CustomHeadLayer.translateToHead without the head's own turn.
			poseStack.pushPose();
			poseStack.rotateDegrees(Axis.YP, 180f - state.bodyRot());
			poseStack.scale(-1, -1, 1);
			poseStack.scale(0.9375f, 0.9375f, 0.9375f);
			poseStack.translate(0, -1.501f, 0);
			poseStack.translate(0, -0.25f, 0);
			poseStack.rotateDegrees(Axis.YP, 180);
			poseStack.scale(0.625f, -0.625f, -0.625f);
			state.back().submit(poseStack, collector, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
			poseStack.popPose();
		}
	}

	/** One cosmetic, centred and fitted into the box, turning. */
	private static void item(State state, PoseStack poseStack, SubmitNodeCollector collector) {
		ItemStackRenderState item = state.back();
		if (item == null || item.isEmpty()) {
			return;
		}
		AABB box = item.getModelBoundingBox();
		double size = Math.max(box.getXsize(), Math.max(box.getYsize(), box.getZsize()));
		float fit = size <= 0 ? 1 : (float) (1.0 / size);
		poseStack.rotate(Axis.ZP, (float) Math.PI);
		poseStack.rotateDegrees(Axis.XP, -20);
		poseStack.rotateDegrees(Axis.YP, state.spin());
		poseStack.scale(fit, fit, fit);
		poseStack.translate(-box.getCenter().x, -box.getCenter().y, -box.getCenter().z);
		item.submit(poseStack, collector, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
	}

	@Override
	protected float getTranslateY(int height, int guiScale) {
		return height / 2f;
	}

	@Override
	protected String getTextureLabel() {
		return "minehouse wardrobe";
	}
}
