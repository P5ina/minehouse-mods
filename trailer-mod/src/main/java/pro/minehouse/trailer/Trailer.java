package pro.minehouse.trailer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import com.mojang.math.Transformation;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * The trailer: 16 bars at 126 BPM (30.5 s), cut shot by shot to the music. Everything that moves
 * (NPCs, the meteor, the camera, the time of day) lives only on this client; scenery that has to be
 * real blocks (campfire, ore, crater, pool, farm) is put into the singleplayer world for the length
 * of the shot that needs it and taken back afterwards.
 */
public final class Trailer {
	static final double BPM = 126;
	static final double BAR = 240 / BPM;
	static final double LENGTH = 16 * BAR;
	static final double IMPACT = 8 * BAR;
	private static final double PREROLL = 2.0;
	static final Identifier MUSIC = Identifier.fromNamespaceAndPath(MinehouseTrailer.MOD_ID, "music");

	/** Where the camera is and what it aims at; yaw and pitch are worked out from the two. */
	record Cam(Vec3 pos, Vec3 target, float fov) {
	}

	interface ShotTick {
		void tick(double tl, double u);
	}

	interface ShotCam {
		Cam cam(double tl, double u);
	}

	record Shot(double bar, double bars, int dayTime, Runnable enter, ShotTick tick, ShotCam cam) {
		double start() {
			return bar * BAR;
		}

		double length() {
			return bars * BAR;
		}
	}

	private static final Minecraft MC = Minecraft.getInstance();
	private static final List<Shot> SHOTS = new ArrayList<>();
	private static final List<Npc> NPCS = new ArrayList<>();
	private static final Map<BlockPos, BlockState> SAVED = new LinkedHashMap<>(); // server thread only

	static BlockPos origin;
	private static boolean playing;
	private static long prerollUntil;
	private static long startNanos;
	private static boolean started;
	private static double fromSeconds;
	private static int shotIndex = -1;
	private static boolean fresh;
	private static @Nullable SoundInstance music;
	private static boolean impactDone;
	private static Display.@Nullable BlockDisplay meteor;
	private static @Nullable GameType gameTypeBefore;
	private static final boolean[] destroyed = new boolean[8];

	private Trailer() {
	}

	// ---------------------------------------------------------------- control

	static String play(double fromBar) {
		IntegratedServer server = MC.getSingleplayerServer();
		if (server == null || MC.level == null || MC.player == null) {
			return "Трейлер снимается только в одиночном мире.";
		}
		if (origin == null) {
			return "Сначала встаньте на ровное место и введите /trailer stage";
		}
		if (playing) {
			stop();
		}
		buildShots();
		gameTypeBefore = MC.gameMode != null ? MC.gameMode.getPlayerMode() : null;
		String name = MC.player.getGameProfile().name();
		BlockPos o = origin;
		command(server, "gamemode spectator " + name);
		command(server, "tp " + name + " " + o.getX() + " " + (o.getY() + 2) + " " + o.getZ());
		command(server, "weather clear");
		command(server, "gamerule advance_weather false");
		command(server, "gamerule spawn_mobs false");
		command(server, "gamerule random_tick_speed 0");
		ClientLevel level = MC.level;
		List<String> skins = Config.skins();
		for (int i = 0; i < 24; i++) {
			NPCS.add(new Npc(level, i, skins.isEmpty() ? null : skins.get(i % skins.size())));
		}
		fromSeconds = Math.max(0, fromBar) * BAR;
		playing = true;
		started = false;
		impactDone = fromSeconds > IMPACT;
		shotIndex = -1;
		java.util.Arrays.fill(destroyed, false);
		prerollUntil = System.nanoTime() + (long) (PREROLL * 1e9);
		return null;
	}

	static void stop() {
		if (!playing) {
			return;
		}
		playing = false;
		if (music != null) {
			MC.getSoundManager().stop(music);
			music = null;
		}
		ClientLevel level = MC.level;
		for (Npc npc : NPCS) {
			if (npc.inLevel && level != null) {
				level.removeEntity(npc.getId(), Entity.RemovalReason.DISCARDED);
			}
		}
		NPCS.clear();
		removeMeteor();
		IntegratedServer server = MC.getSingleplayerServer();
		if (server != null) {
			server.execute(Trailer::restoreBlocks);
			if (gameTypeBefore != null && MC.player != null) {
				command(server, "gamemode " + gameTypeBefore.getName() + " " + MC.player.getGameProfile().name());
			}
		}
		shotIndex = -1;
	}

	static boolean playing() {
		return playing;
	}

	/** Seconds into the trailer, or a negative number during the black pre-roll. */
	static double now() {
		if (!started) {
			return -1;
		}
		return fromSeconds + (System.nanoTime() - startNanos) / 1e9;
	}

	// ---------------------------------------------------------------- per tick

	static void tick() {
		if (!playing) {
			return;
		}
		if (MC.level == null || MC.getSingleplayerServer() == null) {
			stop();
			return;
		}
		if (!started) {
			long nowNs = System.nanoTime();
			if (nowNs < prerollUntil) {
				return;
			}
			if (music == null && fromSeconds == 0 && Config.hasMusic()) {
				music = new SimpleSoundInstance(MUSIC, SoundSource.MASTER, 1.0F, 1.0F, SoundInstance.createUnseededRandom(), false, 0,
						SoundInstance.Attenuation.NONE, 0, 0, 0, true);
				MC.getSoundManager().play(music);
			}
			// The clock starts when the music is actually heard, so the cuts land on the beat.
			if (music != null && !MC.getSoundManager().isActive(music) && nowNs < prerollUntil + 3_000_000_000L) {
				return;
			}
			started = true;
			startNanos = System.nanoTime();
		}
		MC.gui.toastManager().clear();
		double t = now();
		if (t >= LENGTH + 0.3) {
			stop();
			return;
		}
		int index = shotAt(t);
		if (index != shotIndex) {
			shotIndex = index;
			fresh = true;
			for (Npc npc : NPCS) {
				npc.reset();
			}
			enter(SHOTS.get(index));
		}
		Shot shot = SHOTS.get(index);
		double tl = t - shot.start();
		used = 0;
		shot.tick().tick(tl, Mth.clamp(tl / shot.length(), 0, 1));
		for (int i = used; i < NPCS.size(); i++) {
			hide(NPCS.get(i));
		}
		fresh = false;
		meteorTick(t);
	}

	private static int shotAt(double t) {
		int index = 0;
		for (int i = 0; i < SHOTS.size(); i++) {
			if (t >= SHOTS.get(i).start() - 1e-6) {
				index = i;
			}
		}
		return index;
	}

	private static void enter(Shot shot) {
		IntegratedServer server = MC.getSingleplayerServer();
		if (server != null) {
			server.execute(Trailer::restoreBlocks);
		}
		shot.enter().run();
	}

	// ---------------------------------------------------------------- camera and clock (per frame)

	/** {x, y, z, yaw, pitch, fov} for this frame. */
	static double @Nullable [] camera() {
		if (!playing || !started || shotIndex < 0) {
			return null;
		}
		double t = now();
		int index = shotAt(t);
		Shot shot = SHOTS.get(index);
		double tl = t - shot.start();
		Cam cam = shot.cam().cam(tl, Mth.clamp(tl / shot.length(), 0, 1));
		Vec3 target = cam.target();
		Vec3 pos = clearView(index, cam.pos(), target);
		double dx = target.x - pos.x;
		double dy = target.y - pos.y;
		double dz = target.z - pos.z;
		double yaw = Math.toDegrees(Math.atan2(-dx, dz));
		double pitch = -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
		double shake = t > IMPACT && t < IMPACT + 1.2 ? 0.45 * (1 - (t - IMPACT) / 1.2) : 0;
		if (shake > 0) {
			pos = pos.add(Math.sin(t * 93) * shake, Math.sin(t * 71 + 1) * shake, Math.sin(t * 57 + 2) * shake);
			yaw += Math.sin(t * 47) * shake * 2;
		}
		return new double[] {pos.x, pos.y, pos.z, yaw, pitch, cam.fov()};
	}

	private static int liftShot = -1;
	private static double lift;

	/**
	 * Every world is different, so a planned camera spot can end up inside a hill or behind a tree.
	 * The camera then rises until it sees its target, and within a shot it only ever rises, so it
	 * doesn't jump up and down; if even 16 blocks up is blocked, it moves in front of the obstacle.
	 */
	private static Vec3 clearView(int shot, Vec3 pos, Vec3 target) {
		if (shot != liftShot) {
			liftShot = shot;
			lift = 0;
		}
		double floor = groundTop(pos.x, pos.z) + 0.6;
		if (pos.y < floor) {
			pos = new Vec3(pos.x, floor, pos.z);
		}
		for (double up = lift; up <= 16; up += 0.5) {
			Vec3 p = pos.add(0, up, 0);
			if (!blocked(target, p)) {
				lift = up;
				return p;
			}
		}
		Vec3 p = pos.add(0, lift, 0);
		BlockHitResult hit = MC.level.clip(new ClipContext(target, p, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()));
		return hit.getLocation().add(target.subtract(p).normalize().scale(0.4));
	}

	/** Is anything solid between the camera and what it looks at (not counting the target's own block or crop)? */
	private static boolean blocked(Vec3 target, Vec3 pos) {
		Vec3 from = target.add(pos.subtract(target).normalize().scale(1.5));
		return MC.level.clip(new ClipContext(from, pos, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()))
				.getType() != HitResult.Type.MISS;
	}

	/** The time of day the sky shows, or -1 to leave the world's own. */
	static long dayTime() {
		if (!playing) {
			return -1;
		}
		if (!started || shotIndex < 0) {
			return 18000;
		}
		return SHOTS.get(shotAt(now())).dayTime();
	}

	// ---------------------------------------------------------------- overlay

	static boolean hidesHud() {
		return playing;
	}

	static void overlay(GuiGraphicsExtractor g, DeltaTracker delta) {
		if (!playing) {
			return;
		}
		int w = g.guiWidth();
		int h = g.guiHeight();
		if (!started) {
			g.fill(0, 0, w, h, 0xFF000000);
			return;
		}
		double t = now();
		Font font = MC.font;
		// vignette-ish letterbox edges
		g.fillGradient(0, 0, w, h / 7, 0x66000000, 0x00000000);
		g.fillGradient(0, h - h / 7, w, h, 0x00000000, 0x66000000);
		if (t < 6 * BAR + 0.0 && t > 0) {
			double a = smooth((t - 0.5) / 0.8) * (1 - smooth((t - 3.2) / 0.5));
			bigText(g, font, "MINEHOUSE", w / 2, (int) (h * 0.40), h / 75f * 1.0f, a, 0xFFFFFF);
			double b = smooth((t - 1.1) / 0.8) * (1 - smooth((t - 3.2) / 0.5));
			bigText(g, font, "ванильный+ сервер", w / 2, (int) (h * 0.53), h / 220f, b, 0xFFFFFF);
		}
		caption(g, font, t, 8 * BAR + 0.35, 9.5 * BAR - 0.15, "ИВЕНТЫ ДЛЯ ВСЕГО СЕРВЕРА");
		caption(g, font, t, 9.5 * BAR + 0.05, 10 * BAR - 0.05, "ЭЛИТРЫ, НЕЗЕР, ЭНД");
		caption(g, font, t, 12.5 * BAR + 0.05, 13 * BAR + 0.4, "ИГРАЙ ВМЕСТЕ С НАМИ");
		if (t > IMPACT && t < IMPACT + 0.4) {
			int a = (int) (255 * (1 - (t - IMPACT) / 0.4));
			g.fill(0, 0, w, h, (a << 24) | 0xFFF0D2);
		}
		if (t >= 15 * BAR) {
			double tl = t - 15 * BAR;
			int dark = (int) (140 * smooth(tl / 0.3));
			g.fill(0, 0, w, h, dark << 24 | 0x050814);
			bigText(g, font, "MINEHOUSE", w / 2, (int) (h * 0.33), h / 85f, smooth(tl / 0.25), 0xFFFFFF);
			bigText(g, font, "play.minehouse.pro", w / 2, (int) (h * 0.50), h / 160f, smooth((tl - 0.15) / 0.25), 0xFFFFFF);
			bigText(g, font, "discord.gg/gCYGxDzaCb", w / 2, (int) (h * 0.60), h / 250f, smooth((tl - 0.3) / 0.25), 0xDDDDDD);
			double out = smooth((tl - (BAR - 0.45)) / 0.45);
			if (out > 0) {
				g.fill(0, 0, w, h, (int) (255 * out) << 24);
			}
		}
		double in = 1 - smooth(t / 0.35);
		if (in > 0) {
			g.fill(0, 0, w, h, (int) (255 * in) << 24);
		}
	}

	private static void caption(GuiGraphicsExtractor g, Font font, double t, double from, double to, String text) {
		if (t < from || t > to) {
			return;
		}
		double a = smooth((t - from) / 0.15) * (1 - smooth((t - (to - 0.15)) / 0.15));
		bigText(g, font, text, g.guiWidth() / 2, (int) (g.guiHeight() * 0.80), g.guiHeight() / 190f, a, 0xFFFFFF);
	}

	private static void bigText(GuiGraphicsExtractor g, Font font, String text, int x, int y, float scale, double alpha, int rgb) {
		int a = (int) (Mth.clamp(alpha, 0, 1) * 255);
		if (a < 8) {
			return;
		}
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(scale, scale);
		g.centeredText(font, text, 0, -font.lineHeight / 2, a << 24 | rgb);
		g.pose().popMatrix();
	}

	// ---------------------------------------------------------------- helpers for shots

	private static int used;

	private static Npc npc(int i) {
		Npc npc = NPCS.get(i);
		used = Math.max(used, i + 1);
		return npc;
	}

	/** Places NPC {@code i} for this tick; on the first tick of a shot it appears there instead of walking. */
	private static Npc put(int i, Vec3 pos, float yaw, float pitch) {
		Npc npc = npc(i);
		ClientLevel level = MC.level;
		if (!npc.inLevel) {
			npc.teleport(pos, yaw);
			level.addEntity(npc);
			npc.inLevel = true;
		} else if (fresh) {
			npc.teleport(pos, yaw);
		}
		npc.moveTo(pos, yaw, pitch);
		return npc;
	}

	private static void hide(Npc npc) {
		if (npc.inLevel && MC.level != null) {
			MC.level.removeEntity(npc.getId(), Entity.RemovalReason.DISCARDED);
			npc.inLevel = false;
		}
	}

	/** World position of a point given relative to the stage, standing on the ground. */
	static Vec3 at(double dx, double dz) {
		double x = origin.getX() + 0.5 + dx;
		double z = origin.getZ() + 0.5 + dz;
		return new Vec3(x, ground(x, z), z);
	}

	static Vec3 at(double dx, double dy, double dz) {
		return at(dx, dz).add(0, dy, 0);
	}

	/** Y of the first free block above solid ground (ignoring leaves). */
	static double ground(double x, double z) {
		return MC.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z));
	}

	private static double groundTop(double x, double z) {
		return MC.level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z));
	}

	static float yawTo(Vec3 from, Vec3 to) {
		return (float) Math.toDegrees(Math.atan2(-(to.x - from.x), to.z - from.z));
	}

	static Cam look(Vec3 from, Vec3 to, double fov) {
		return new Cam(from, to, (float) fov);
	}

	static Vec3 lerp(Vec3 a, Vec3 b, double t) {
		return a.add(b.subtract(a).scale(t));
	}

	static double smooth(double t) {
		t = Mth.clamp(t, 0, 1);
		return t * t * (3 - 2 * t);
	}

	static double inOut(double t) {
		t = Mth.clamp(t, 0, 1);
		return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
	}

	/** Swings an arm {@code perSecond} times a second, in step with the trailer clock. */
	private static void swingEvery(Npc npc, double tl, double period, double phase) {
		double prev = tl - 0.05;
		if (Math.floor((tl + phase) / period) != Math.floor((prev + phase) / period)) {
			npc.swingArm();
		}
	}

	private static boolean crossed(double tl, double at) {
		return tl >= at && tl - 0.05 < at;
	}

	private static void particles(ParticleOptions type, Vec3 p, int count, double spread, double speed) {
		ClientLevel level = MC.level;
		RandomSource r = level.getRandom();
		for (int i = 0; i < count; i++) {
			level.addAlwaysVisibleParticle(type, true, p.x + (r.nextDouble() - .5) * spread, p.y + (r.nextDouble() - .5) * spread,
					p.z + (r.nextDouble() - .5) * spread, (r.nextDouble() - .5) * speed, r.nextDouble() * speed, (r.nextDouble() - .5) * speed);
		}
	}

	private static void sound(Vec3 p, SoundEvent sound, float volume, float pitch) {
		MC.level.playLocalSound(p.x, p.y, p.z, sound, SoundSource.MASTER, volume, pitch, false);
	}

	// ---------------------------------------------------------------- real blocks (server thread)

	private static void command(IntegratedServer server, String cmd) {
		server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), cmd));
	}

	private static void blocks(Consumer<ServerLevel> edit) {
		IntegratedServer server = MC.getSingleplayerServer();
		if (server == null || MC.level == null) {
			return;
		}
		var dimension = MC.level.dimension();
		server.execute(() -> {
			ServerLevel level = server.getLevel(dimension);
			if (level != null) {
				edit.accept(level);
			}
		});
	}

	/** Changes a block and remembers the old one. No shape updates: grass next to a hole must not pop off. */
	private static void set(ServerLevel level, BlockPos pos, BlockState state) {
		SAVED.putIfAbsent(pos.immutable(), level.getBlockState(pos));
		level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
	}

	/** Remembers a whole box, so whatever water spills or falls inside it is put back too. */
	private static void protect(ServerLevel level, BlockPos center, int radius, int down, int up) {
		for (BlockPos p : BlockPos.betweenClosed(center.offset(-radius, -down, -radius), center.offset(radius, up, radius))) {
			SAVED.putIfAbsent(p.immutable(), level.getBlockState(p));
		}
	}

	private static void restoreBlocks() {
		IntegratedServer server = MC.getSingleplayerServer();
		if (server == null || SAVED.isEmpty()) {
			SAVED.clear();
			return;
		}
		ServerLevel level = server.getLevel(MC.level != null ? MC.level.dimension() : net.minecraft.world.level.Level.OVERWORLD);
		if (level != null) {
			List<Map.Entry<BlockPos, BlockState>> list = new ArrayList<>(SAVED.entrySet());
			for (int i = list.size() - 1; i >= 0; i--) {
				level.setBlock(list.get(i).getKey(), list.get(i).getValue(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
			}
		}
		SAVED.clear();
	}

	private static BlockPos block(Vec3 v) {
		return BlockPos.containing(v);
	}

	// ---------------------------------------------------------------- meteor

	static Vec3 impactPoint() {
		return at(30, -24);
	}

	private static Vec3 meteorStart() {
		return impactPoint().add(60, 80, -50);
	}

	private static void meteorTick(double t) {
		ClientLevel level = MC.level;
		double fallFrom = IMPACT - BAR;
		if (t >= fallFrom && t < IMPACT) {
			if (meteor == null) {
				meteor = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, level);
				meteor.setId(-731_900);
				meteor.setBlockState(Blocks.MAGMA_BLOCK.defaultBlockState());
				meteor.setTransformation(new Transformation(new Vector3f(-1.5f, -1.5f, -1.5f), new Quaternionf(), new Vector3f(3f), new Quaternionf()));
				Vec3 p = meteorPos(t);
				meteor.snapTo(p.x, p.y, p.z, 0, 0);
				level.addEntity(meteor);
			}
			Vec3 p = meteorPos(t);
			meteor.setPos(p);
			Vec3 back = meteorStart().subtract(impactPoint()).normalize();
			for (int i = 0; i < 6; i++) {
				Vec3 q = p.add(back.scale(i * 1.2));
				particles(ParticleTypes.FLAME, q, 6, 1.6, 0.05);
				particles(ParticleTypes.LARGE_SMOKE, q.add(back.scale(3)), 2, 2, 0.02);
			}
			particles(ParticleTypes.LAVA, p, 3, 1.5, 0.2);
		} else {
			removeMeteor();
		}
		if (!impactDone && t >= IMPACT) {
			impactDone = true;
			impact();
		}
		if (t > IMPACT && t < IMPACT + 2 * BAR) {
			Vec3 c = impactPoint();
			particles(ParticleTypes.FLAME, c.add(0, 1, 0), 10, 3, 0.08);
			particles(ParticleTypes.CAMPFIRE_COSY_SMOKE, c.add(0, 1.5, 0), 2, 3, 0.05);
		}
	}

	private static Vec3 meteorPos(double t) {
		double k = Mth.clamp((IMPACT - t) / BAR, 0, 1);
		return lerp(impactPoint(), meteorStart(), k * k);
	}

	private static void removeMeteor() {
		if (meteor != null) {
			if (MC.level != null) {
				MC.level.removeEntity(meteor.getId(), Entity.RemovalReason.DISCARDED);
			}
			meteor = null;
		}
	}

	private static void impact() {
		Vec3 c = impactPoint();
		ClientLevel level = MC.level;
		for (int i = 0; i < 3; i++) {
			level.addAlwaysVisibleParticle(ParticleTypes.EXPLOSION_EMITTER, true, c.x + i - 1, c.y + 1, c.z, 0, 0, 0);
		}
		particles(ParticleTypes.FLAME, c.add(0, 1, 0), 400, 4, 1.4);
		particles(ParticleTypes.LAVA, c.add(0, 1, 0), 80, 3, 1.0);
		particles(ParticleTypes.LARGE_SMOKE, c.add(0, 2, 0), 150, 6, 0.4);
		particles(ParticleTypes.FIREWORK, c.add(0, 2, 0), 120, 2, 1.2);
		sound(c, SoundEvents.GENERIC_EXPLODE.value(), 6, 0.55F);
		sound(c, SoundEvents.LIGHTNING_BOLT_THUNDER, 4, 0.8F);
	}

	/** The crater: real blocks for as long as the shots after the impact need it. */
	private static void crater() {
		Vec3 c = impactPoint();
		blocks(level -> {
			RandomSource r = RandomSource.create(42);
			BlockPos center = block(c).below();
			for (int dx = -5; dx <= 5; dx++) {
				for (int dz = -5; dz <= 5; dz++) {
					double d = Math.sqrt(dx * dx + dz * dz);
					if (d > 5.2) {
						continue;
					}
					int depth = d < 2 ? 2 : d < 3.5 ? 1 : 0;
					BlockPos top = center.offset(dx, 0, dz);
					top = new BlockPos(top.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, top.getX(), top.getZ()) - 1, top.getZ());
					for (int k = 0; k < depth; k++) {
						set(level, top.below(k), Blocks.AIR.defaultBlockState());
					}
					BlockPos floor = top.below(depth);
					float roll = r.nextFloat();
					BlockState s = d < 2.5 ? (roll < .6 ? Blocks.MAGMA_BLOCK : Blocks.BLACKSTONE).defaultBlockState()
							: roll < .35 ? Blocks.MAGMA_BLOCK.defaultBlockState()
							: roll < .7 ? Blocks.BLACKSTONE.defaultBlockState()
							: Blocks.COARSE_DIRT.defaultBlockState();
					set(level, floor, s);
				}
			}
			// The meteor itself, half buried, and smoke from the cracks.
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = 0; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						if (Math.abs(dx) + Math.abs(dz) + dy <= 2) {
							set(level, center.offset(dx, dy - 1, dz), (dx + dz + dy) % 2 == 0 ? Blocks.MAGMA_BLOCK.defaultBlockState()
									: Blocks.BLACKSTONE.defaultBlockState());
						}
					}
				}
			}
			for (int[] o : new int[][] {{3, 2}, {-3, 1}, {1, -3}}) {
				BlockPos p = center.offset(o[0], 0, o[1]);
				p = new BlockPos(p.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ()), p.getZ());
				set(level, p, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true).setValue(CampfireBlock.SIGNAL_FIRE, true));
			}
		});
	}

	// ---------------------------------------------------------------- the shot list

	private static void buildShots() {
		SHOTS.clear();
		final ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
		final ItemStack sword = new ItemStack(Items.IRON_SWORD);
		final ItemStack planks = new ItemStack(Items.OAK_PLANKS);
		final ItemStack torch = new ItemStack(Items.TORCH);

		// 1-2. Sunrise, flying in over the spawn; people mill about.
		SHOTS.add(new Shot(0, 2, 23300, () -> { }, (tl, u) -> crowd(tl, 10, false),
				(tl, u) -> {
					double k = inOut(u);
					Vec3 o = at(0, 0);
					Vec3 p = new Vec3(o.x + Mth.lerp(k, -42, -14), o.y + Mth.lerp(u, 30, 14), o.z + Mth.lerp(k, -46, -16));
					return look(p, o.add(0, 1, 0), 60);
				}));

		// 3-4. Day: five friends walk across a meadow, camera tracks alongside.
		SHOTS.add(new Shot(2, 2, 6000, () -> { }, (tl, u) -> {
			for (int i = 0; i < 5; i++) {
				Vec3 p = at(20 + i * 2.2 + tl * 2.6, -6 + Math.sin(i * 2) * 0.8);
				put(i, p, -90, 0);
			}
		}, (tl, u) -> {
			Vec3 mid = at(24 + tl * 2.6, -6);
			return look(mid.add(-1, 3, 9), mid.add(1, 1, 0), 55);
		}));

		// 5-6. Golden hour: mining a vein of diamonds, camera orbits.
		final Vec3[] ore = new Vec3[1];
		SHOTS.add(new Shot(4, 2, 11300, () -> {
			java.util.Arrays.fill(destroyed, false);
			ore[0] = at(-22, 10);
			placeOre(ore[0]);
		}, (tl, u) -> {
			Vec3 c = ore[0];
			Npc a = put(0, c.add(0, 0, 1.6), 180, 20);
			a.hold(pick);
			swingEvery(a, tl, 0.42, 0);
			Npc b = put(1, c.add(2.4, 0, 0.9), 120, 25);
			b.hold(pick);
			swingEvery(b, tl, 0.5, 0.2);
			if (crossed(tl, 1.0)) {
				breakOre(c.add(0, 1, 0));
			}
			if (crossed(tl, 2.6)) {
				breakOre(c);
			}
		}, (tl, u) -> {
			Vec3 c = ore[0];
			double a = Math.toRadians(Mth.lerp(u, -40, 30));
			return look(c.add(Math.sin(a) * 7.5, 3.5, Math.cos(a) * 7.5), c.add(0, 1.2, 0.6), 55);
		}));

		// 7. Night: a campfire at spawn, friends around it.
		SHOTS.add(new Shot(6, 1, 18000, () -> blocks(level -> set(level, block(at(0, 0)),
				Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true))), (tl, u) -> {
			Vec3 c = at(0, 0);
			for (int i = 0; i < 6; i++) {
				double a = i / 6.0 * Math.PI * 2 + 0.3;
				Vec3 p = c.add(Math.cos(a) * 2.6, 0, Math.sin(a) * 2.6);
				Npc n = put(i, p, yawTo(p, c), 15 + (float) Math.sin(tl * 2 + i) * 8);
				if (i == 2) {
					n.hold(torch);
				}
				if (i == 4) {
					swingEvery(n, tl, 0.6, 0);
				}
			}
		}, (tl, u) -> {
			Vec3 c = at(0, 0);
			return look(c.add(Mth.lerp(u, 6.5, 4.8), Mth.lerp(u, 2.6, 2.0), Mth.lerp(u, 5.5, 4.0)), c.add(0, 0.8, 0), 55);
		}));

		// 8. Build-up, four quick shots on the beat.
		SHOTS.add(new Shot(7, 0.25, 12600, () -> { }, (tl, u) -> {
			Vec3 c = at(8, -4);
			Npc n = put(0, c, 0, 35);
			n.hold(planks);
			if (crossed(tl, 0.12)) {
				n.swingArm();
			}
			if (crossed(tl, 0.18)) {
				BlockPos b = block(c.add(0, 0, 1.6));
				blocks(level -> set(level, b, Blocks.OAK_PLANKS.defaultBlockState()));
				sound(c, Blocks.OAK_PLANKS.defaultBlockState().getSoundType().getPlaceSound(), 1, 1);
			}
		}, (tl, u) -> {
			Vec3 c = at(8, -4);
			return look(c.add(3.4, 1.8, 1.6), c.add(0, 1.0, 1.0), 50);
		}));
		SHOTS.add(new Shot(7.25, 0.25, 12600, () -> placeOre(ore[0]), (tl, u) -> {
			Vec3 c = ore[0];
			Npc a = put(0, c.add(0, 0, 1.6), 180, 20);
			a.hold(pick);
			if (crossed(tl, 0.05)) {
				a.swingArm();
			}
			if (crossed(tl, 0.22)) {
				breakOre(c.add(0, 1, 0));
			}
		}, (tl, u) -> {
			Vec3 c = ore[0];
			return look(c.add(-1.8, 2.2, 3.8), c.add(0, 1.4, 0.4), 45);
		}));
		SHOTS.add(new Shot(7.5, 0.25, 18000, () -> { }, (tl, u) -> { },
				(tl, u) -> {
					Vec3 m = impactPoint();
					return look(m.add(-22, 14, 8), meteorPos(7.5 * BAR + tl).add(0, -6, 0), 70);
				}));
		SHOTS.add(new Shot(7.75, 0.25, 18000, () -> { }, (tl, u) -> {
			Vec3 p = impactPoint().add(-5, 0, 6);
			Npc n = put(0, new Vec3(p.x, ground(p.x, p.z), p.z), yawTo(p, impactPoint()) - 25, -45);
			n.hold(ItemStack.EMPTY);
		}, (tl, u) -> {
			Vec3 p = impactPoint().add(-5, 0, 6);
			Vec3 feet = new Vec3(p.x, ground(p.x, p.z), p.z);
			float yaw = yawTo(p, impactPoint()) - 25;
			double r = Math.toRadians(yaw);
			Vec3 front = new Vec3(-Math.sin(r), 0, Math.cos(r));
			return look(feet.add(front.scale(2.2)).add(0, 1.3, 0), feet.add(0, 1.7, 0), 45);
		}));

		// 9. The drop: impact.
		SHOTS.add(new Shot(8, 1, 18000, Trailer::crater, (tl, u) -> {
			Vec3 c = impactPoint();
			Vec3 dir = new Vec3(-12, 0, 8).normalize();
			Vec3 side = new Vec3(-dir.z, 0, dir.x);
			for (int i = 0; i < 8; i++) {
				double s = (i % 2 == 0 ? 1 : -1) * (3.4 + (i / 2) * 1.3);
				Vec3 flat = c.add(dir.scale(7 + i % 3)).add(side.scale(s));
				Vec3 p = new Vec3(flat.x, ground(flat.x, flat.z), flat.z);
				double jump = tl > 0.3 ? Math.abs(Math.sin(tl * 6.6 + i)) * 0.5 : 0;
				Npc n = put(i, p.add(0, jump, 0), yawTo(p, c), tl > 0.3 ? -10 : -30);
				if (tl > 0.3) {
					swingEvery(n, tl, 0.48, i * 0.1);
				}
			}
		}, (tl, u) -> {
			Vec3 c = impactPoint();
			return look(c.add(-13, 4.5, 9), c.add(0, 2.5, 0), 62);
		}));

		// 10. Everyone around the crater.
		SHOTS.add(new Shot(9, 0.5, 18000, Trailer::crater, (tl, u) -> {
			Vec3 c = impactPoint();
			for (int i = 0; i < 14; i++) {
				double a = i / 14.0 * Math.PI * 2;
				double r = 6.8 + i % 3;
				Vec3 flat = c.add(Math.cos(a) * r, 0, Math.sin(a) * r);
				Vec3 p = new Vec3(flat.x, ground(flat.x, flat.z), flat.z);
				Npc n = put(i, p.add(0, Math.abs(Math.sin(tl * 6.6 + i * 0.7)) * 0.5, 0), yawTo(p, c), -5);
				swingEvery(n, tl, 0.48, i * 0.07);
			}
		}, (tl, u) -> {
			Vec3 c = impactPoint();
			double a = Mth.lerp(u, 0.4, 0.8);
			return look(c.add(Math.cos(a) * 15, 4.5, Math.sin(a) * 15), c.add(0, 1.5, 0), 55);
		}));

		// 11. Elytra flight.
		SHOTS.add(new Shot(9.5, 0.5, 6000, () -> { }, (tl, u) -> {
			Vec3 p = flyer(u);
			Npc n = put(0, p, yawTo(flyer(0), flyer(1)), 10);
			n.wear(new ItemStack(Items.ELYTRA));
			n.flying(true);
		}, (tl, u) -> {
			Vec3 p = flyer(u);
			Vec3 dir = flyer(1).subtract(flyer(0)).normalize();
			return look(p.subtract(dir.scale(7)).add(1.2, 2.2, 0), p.add(dir.scale(5)), 70);
		}));

		// 12. Sword duel.
		SHOTS.add(new Shot(10, 0.5, 11800, () -> { }, (tl, u) -> {
			Vec3 c = at(-2, -38);
			Npc a = put(0, at(-3 + Math.sin(tl * 6) * .15, -38), -90, 0);
			Npc b = put(1, at(-1 - Math.sin(tl * 6 + 1) * .15, -38), 90, 0);
			a.hold(sword);
			b.hold(sword);
			swingEvery(a, tl, 0.3, 0);
			swingEvery(b, tl, 0.3, 0.15);
			if (crossed(tl, 0.3) || crossed(tl, 0.6)) {
				particles(ParticleTypes.CRIT, c.add(0, 1.4, 0), 12, 0.6, 0.4);
				sound(c, SoundEvents.PLAYER_ATTACK_CRIT, 1, 1);
			}
		}, (tl, u) -> {
			Vec3 c = at(-2, -38);
			return look(c.add(Mth.lerp(u, -0.7, 0.7), 1.7, 4.2), c.add(0, 1.3, 0), 50);
		}));

		// 13. Jump into a pool.
		SHOTS.add(new Shot(10.5, 0.5, 6000, () -> pool(at(22, 22)), (tl, u) -> {
			Vec3 l = at(22, 22);
			Vec3 top = new Vec3(l.x - 4, l.y + 3, l.z);
			double k = Mth.clamp((tl - 0.12) / 0.55, 0, 1);
			Vec3 p = lerp(top, l.add(0, -1.2, 0), k).add(0, Math.sin(k * Math.PI) * 2.2, 0);
			put(0, p, -90, 20);
			if (crossed(tl, 0.67)) {
				particles(ParticleTypes.SPLASH, l.add(0, 0, 0), 120, 2, 0.6);
				particles(ParticleTypes.BUBBLE, l.add(0, -0.8, 0), 40, 1.5, 0.2);
				sound(l, SoundEvents.GENERIC_SPLASH, 1.2F, 1);
			}
		}, (tl, u) -> {
			Vec3 l = at(22, 22);
			return look(l.add(1.5, 3.2, 8.5), l.add(-1.5, 1.8, 0), 60);
		}));

		// 14. Crops shoot up.
		SHOTS.add(new Shot(11, 0.5, 11800, () -> {
			lastAge = 0;
			farm(at(-16, -18), 0);
		}, (tl, u) -> {
			Vec3 f = at(-16, -18);
			put(0, at(-19 + tl * 2.2, -15), -90, 30);
			int age = Math.min(7, (int) (u * 9));
			if (age != lastAge) {
				lastAge = age;
				farm(f, age);
			}
		}, (tl, u) -> {
			Vec3 f = at(-16, -18);
			return look(f.add(Mth.lerp(u, -4, -2), 4.5, 8), f.add(0, 0.5, 0), 55);
		}));

		// 15. Dance at spawn at sunset.
		SHOTS.add(new Shot(11.5, 0.5, 12500, () -> { }, (tl, u) -> {
			double beat = 60 / BPM;
			for (int i = 0; i < 9; i++) {
				Npc n = put(i, at(-4 + i, 2 + (i % 2) * 0.6), 180, 0);
				boolean down = ((int) Math.floor(tl / (beat / 2)) + i) % 2 == 0;
				n.crouch(down);
				n.setYHeadRot(180 + (float) Math.sin(tl * 8 + i) * 30);
			}
		}, (tl, u) -> {
			Vec3 c = at(0, 2);
			return look(c.add(Mth.lerp(u, -2, 2), 2.2, -6.5), c.add(0, 1.2, 0), 55);
		}));

		// 16. A pillar goes up fast.
		SHOTS.add(new Shot(12, 0.5, 6000, () -> lastPillar = 0, (tl, u) -> {
			Vec3 q = at(-8, -8);
			int k = Math.min(6, (int) (u * 7) + 1);
			if (k != lastPillar) {
				int from = lastPillar;
				lastPillar = k;
				BlockPos base = block(q);
				blocks(level -> {
					for (int i = from; i < k; i++) {
						set(level, base.above(i), Blocks.OAK_PLANKS.defaultBlockState());
					}
				});
				sound(q, Blocks.OAK_PLANKS.defaultBlockState().getSoundType().getPlaceSound(), 1, 1);
			}
			Npc n = put(0, q.add(0, k, 0), 0, 70);
			n.hold(planks);
			swingEvery(n, tl, 0.16, 0);
		}, (tl, u) -> {
			Vec3 q = at(-8, -8);
			return look(q.add(5, 4 + u * 3, 5), q.add(0, 3 + u * 2, 0), 55);
		}));

		// 17. Waving at the camera.
		SHOTS.add(new Shot(12.5, 0.5, 12500, () -> { }, (tl, u) -> {
			for (int i = 0; i < 6; i++) {
				Vec3 p = at(-3 + i * 1.2, -1 + Math.abs(i - 2.5) * 0.4);
				double jump = i % 2 == 0 ? Math.abs(Math.sin(tl * 6.6 + i)) * 0.45 : 0;
				Npc n = put(i, p.add(0, jump, 0), 180 + (float) ((i - 2.5) * 6), -5);
				swingEvery(n, tl, 0.24, i * 0.05);
			}
		}, (tl, u) -> {
			Vec3 c = at(0, 0);
			return look(c.add(0, 1.6, -6.5 + u * 0.6), c.add(0, 1.3, 0), 50);
		}));

		// 18-19. Crane up from the crowd to the whole world.
		SHOTS.add(new Shot(13, 2, 12700, () -> { }, (tl, u) -> crowd(tl, 22, true), (tl, u) -> {
			double k = inOut(u);
			Vec3 c = at(0, 0);
			double d = Mth.lerp(k, 9, 46);
			return look(c.add(-d * 0.6, Mth.lerp(k, 3, 48), -d * 0.8), c.add(0, 1, 0), Mth.lerp(u, 55, 65));
		}));

		// 20. End card.
		SHOTS.add(new Shot(15, 1, 12800, () -> { }, (tl, u) -> crowd(tl, 22, true), (tl, u) -> {
			Vec3 c = at(0, 0);
			return look(c.add(-28, 50 + u * 5, -37 - u * 2), c.add(0, 1, 0), 65);
		}));
	}

	private static int lastAge = -1;
	private static int lastPillar;

	private static Vec3 flyer(double u) {
		Vec3 a = at(-60, 10);
		Vec3 b = at(-40, -6);
		double y = Math.max(a.y, b.y) + 26 - u * 3;
		Vec3 p = lerp(a, b, u);
		return new Vec3(p.x, y, p.z);
	}

	/** People walking slow circles round the spawn; on {@code cheer} some of them jump and wave. */
	private static void crowd(double tl, int count, boolean cheer) {
		Vec3 c = at(0, 0);
		for (int i = 0; i < count; i++) {
			double dir = i % 2 == 0 ? 1 : -1;
			double r = 2.4 + (i % 4) * 1.1;
			double a = i / (double) count * Math.PI * 2 + tl * 0.35 * dir / r * 2.4;
			Vec3 flat = c.add(Math.cos(a) * r, 0, Math.sin(a) * r);
			Vec3 p = new Vec3(flat.x, ground(flat.x, flat.z), flat.z);
			boolean waving = cheer && i % 3 == 0;
			// tangent direction of the walk
			Vec3 next = c.add(Math.cos(a + 0.1 * dir) * r, 0, Math.sin(a + 0.1 * dir) * r);
			float yaw = waving ? yawTo(p, c) + 180 : yawTo(flat, next);
			if (waving) {
				Npc n = put(i, p.add(0, Math.abs(Math.sin(tl * 6.6 + i)) * 0.45, 0), yaw, -10);
				swingEvery(n, tl, 0.4, i * 0.05);
			} else {
				put(i, p, yaw, 0);
			}
		}
	}

	private static void placeOre(Vec3 c) {
		BlockPos b = block(c);
		blocks(level -> {
			set(level, b, Blocks.DIAMOND_ORE.defaultBlockState());
			set(level, b.above(), Blocks.DIAMOND_ORE.defaultBlockState());
			set(level, b.east(), Blocks.STONE.defaultBlockState());
			set(level, b.west(), Blocks.STONE.defaultBlockState());
			set(level, b.east().above(), Blocks.STONE.defaultBlockState());
			set(level, b.west().above(), Blocks.COAL_ORE.defaultBlockState());
			set(level, b.above(2), Blocks.STONE.defaultBlockState());
			set(level, b.east(2).south(), Blocks.STONE.defaultBlockState());
			set(level, b.east(3).south(), Blocks.COBBLESTONE.defaultBlockState());
		});
	}

	private static void breakOre(Vec3 p) {
		BlockPos b = block(p);
		blocks(level -> {
			SAVED.putIfAbsent(b.immutable(), level.getBlockState(b));
			level.destroyBlock(b, false);
		});
	}

	private static void pool(Vec3 l) {
		BlockPos c = block(l);
		blocks(level -> {
			protect(level, c, 7, 5, 5);
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					BlockPos top = c.offset(dx, -1, dz);
					set(level, top.above(), Blocks.AIR.defaultBlockState());
					set(level, top, Blocks.WATER.defaultBlockState());
					set(level, top.below(), Blocks.WATER.defaultBlockState());
					if (Math.abs(dx) == 2 || Math.abs(dz) == 2) {
						set(level, top.below(2), Blocks.DIRT.defaultBlockState());
					}
				}
			}
			// Seal the rim, or the water runs off down the slope and washes the grass away.
			for (int dx = -3; dx <= 3; dx++) {
				for (int dz = -3; dz <= 3; dz++) {
					if (Math.abs(dx) < 3 && Math.abs(dz) < 3) {
						continue;
					}
					for (int dy = -2; dy <= -1; dy++) {
						BlockPos rim = c.offset(dx, dy, dz);
						BlockState state = level.getBlockState(rim);
						if (state.canBeReplaced() || !state.isSolid()) {
							set(level, rim, dy == -1 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState());
						}
					}
				}
			}
			for (int y = 0; y < 3; y++) {
				set(level, c.offset(-4, y, 0), Blocks.COBBLESTONE.defaultBlockState());
			}
		});
	}

	private static void farm(Vec3 f, int age) {
		BlockPos c = block(f);
		blocks(level -> {
			protect(level, c, 5, 3, 3);
			for (int dx = -3; dx <= 3; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					BlockPos soil = c.offset(dx, -1, dz);
					if (dz == 0 && dx == 0) {
						set(level, soil, Blocks.WATER.defaultBlockState());
						set(level, soil.above(), Blocks.AIR.defaultBlockState());
						continue;
					}
					set(level, soil, Blocks.FARMLAND.defaultBlockState());
					int a = Math.max(0, Math.min(7, age - ((dx + dz + 7) % 3)));
					set(level, soil.above(), Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, a));
				}
			}
		});
	}
}
