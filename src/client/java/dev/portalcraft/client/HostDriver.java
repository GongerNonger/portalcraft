package dev.portalcraft.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;
import dev.portalcraft.PortalCraft;
import dev.portalcraft.client.world.BlockSolids;
import dev.portalcraft.client.world.WorldExporter;
import dev.portalcraft.client.world.WorldLink;
import dev.portalcraft.host.BspMap;
import dev.portalcraft.host.HostCollision;
import dev.portalcraft.host.HostLink;
import dev.portalcraft.host.LiveEntities;
import dev.portalcraft.host.PlayerCrossings;
import dev.portalcraft.host.HostEvents;
import dev.portalcraft.host.PortalAir;
import dev.portalcraft.host.Proto;
import dev.portalcraft.host.Units;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.sdl.SDLKeyboard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minecraft's half of playing inside a host game. Each tick: follow the host camera, take the
 * host's keyboard, apply the host's teleports, and keep the host's walls as collision. Each frame:
 * report where the player is so the host can put its own player there.
 */
public final class HostDriver {
	private static final Logger LOG = LoggerFactory.getLogger(PortalCraft.MOD_ID);
	/**
	 * Where the host's maps are: -Dportalcraft.mapsDir, else PORTALCRAFT_MAPS (set by the plugin
	 * when it starts Minecraft), else what the plugin says over the link ("PCP1", however Minecraft
	 * was started), else Steam's default library on this PC.
	 */
	private static Path maps() {
		String dir = System.getProperty("portalcraft.mapsDir", System.getenv("PORTALCRAFT_MAPS"));
		if (dir == null || dir.isEmpty()) {
			dir = HostLink.hostMapsDir();
		}
		return Path.of(dir != null ? dir : "D:/SteamLibrary/steamapps/common/Portal/portal/maps");
	}

	private static final boolean[] KEYS = new boolean[256];
	/** HostState.mouse bit i is Minecraft mouse button MOUSE_BUTTONS[i]. */
	private static final int[] MOUSE_BUTTONS = {InputConstants.MOUSE_BUTTON_LEFT, InputConstants.MOUSE_BUTTON_RIGHT, InputConstants.MOUSE_BUTTON_MIDDLE};
	/** The host's mouse buttons Minecraft currently has down, as HostState.mouse bits. */
	private static int buttons;
	private static boolean linked;
	private static int tickSeq;
	/** The next resync follows a respawn (Minecraft's spawn point is not the host's player). */
	private static boolean respawned;
	/** We set hideGui for a scripted scene (and give it back after). */
	private static boolean hidGuiForScene;
	private static Vec3 tickPrevious = Vec3.ZERO, tickCurrent = Vec3.ZERO;
	private static boolean resync;
	private static int teleportAck;
	private static int seq;
	/** The map file that last failed to load: not tried again, unless the maps folder changes. */
	private static Path failedMap;
	/** When the host's current map was loaded (level starts aren't flings). */
	private static long mapLoadedAt;
	/**
	 * Until then, Steve stays with the host's player instead of falling by himself. A level start
	 * places him before the map's lift and doors have reached Minecraft (they're streamed a moment
	 * later): at the start of a chamber he dropped down the lift shaft, hurt, and the lift left
	 * without him.
	 */
	private static long holdWithHostUntil;
	/** Where the level start put him (a fixed place: following the host's live position fed back on itself). */
	private static Vec3 holdAt = Vec3.ZERO;

	private HostDriver() {
	}

	public static boolean linked() {
		return linked;
	}

	/**
	 * Minecraft's light level (0 to 15) for Steve's hand and what he holds, from the host's light
	 * where its player stands, or -1 to leave Minecraft's own (not linked, or the host can't tell).
	 * The void world is at permanent noon, so without this the hand is as bright in a dark test
	 * chamber as in a lit one, and the gun's lights have nothing to glow against.
	 */
	public static int handLightLevel() {
		Proto.HostState s = linked ? HostLink.current() : null;
		if (s == null || s.handLight().x < 0.0) {
			return -1;
		}
		Vec3 l = s.handLight();
		// Linear light to how bright it looks, then onto Minecraft's levels, generously (Portal's rooms
		// read brighter than their light samples say): never below 5, so the hand stays readable.
		double seen = Math.pow(Math.min(1.0, Math.max(l.x, Math.max(l.y, l.z))), 1.0 / 2.2);
		return (int) Math.round(5.0 + 10.0 * Math.sqrt(seen));
	}

	public static boolean isKeyDown(int scancode) {
		return scancode >= 0 && scancode < KEYS.length && KEYS[scancode];
	}

	/** Where Steve stood when the host went away (blocks), while it is away; else null. */
	private static Vec3 heldWithoutHost;
	/** Where the world loaded Steve, while Minecraft (started by Portal) waits for the link; else null. */
	private static Vec3 heldBeforeLink;

	/** START_CLIENT_TICK: before the player moves this tick. */
	public static void tick(Minecraft minecraft) {
		Proto.HostState s = HostLink.current();
		if (s == null || !s.inGame()) {
			if (linked) {
				// Keep the map's walls: the host is probably just restarting, and without them the
				// player would fall out of the world while it's gone.
				LOG.info("PortalCraft: host gone, releasing control");
				releaseAll(minecraft);
				OverlayLink.close();
				WorldLink.close();
				linked = false;
				heldWithoutHost = minecraft.player != null && HostCollision.active() ? minecraft.player.position() : null;
			}
			// ... and hold Steve where he was. The walls stay but nothing takes him through the host's
			// portals any more: quit Portal during an infinite fall and he dropped out of the bottom
			// of the floor portal's hole, was saved far under the map, and died three times over at the
			// next start (the void, then the fall he was still in).
			LocalPlayer left = minecraft.player;
			// Started by Portal and not linked to it yet: the same, from where the world loaded him.
			// The map's walls only come with the link; saved low in another map's stretch of the world,
			// he fell out of the bottom in those few seconds and died twice before the level began.
			if (heldWithoutHost == null && left != null && HostLifecycle.STARTED_BY_HOST && !left.isDeadOrDying()) {
				heldBeforeLink = heldBeforeLink == null ? left.position() : heldBeforeLink;
				left.setPos(heldBeforeLink);
				left.setDeltaMovement(Vec3.ZERO);
				left.resetFallDistance();
			}
			if (heldWithoutHost != null && left != null && HostCollision.active() && !left.isDeadOrDying()) {
				left.setPos(heldWithoutHost);
				left.setDeltaMovement(Vec3.ZERO);
				left.resetFallDistance();
			}
			return;
		}
		heldWithoutHost = null;
		heldBeforeLink = null;
		if (!linked) {
			LOG.info("PortalCraft: host linked ({})", s.map());
			linked = true;
			resync = true; // either side may have restarted: start from where the host's player is now
		}
		loadMap(s.map());
		HostCollision.setPortals(s.portals());
		PlayerCrossings.matched(s.crossMatched());
		gunFollowsHostShots(minecraft, s.shots());
		// Portal's gun holding an object (its effect state 2): Steve's opens its claws and holds, then lets go.
		dev.portalcraft.client.gun.GunAnimation.holding(s.gunEffect() == 2);
		lightFollowsHostPortals(minecraft, s.portals());
		LiveEntities.carrying(s.gunEffect() == 2 ? s.origin() : null);
		List<LiveEntities.Moved> moved = LiveEntities.update(HostLink.entities());
		matchHostWindowSize(minecraft);

		// Losing the link (host restarting) makes Minecraft pause itself; linked again, carry on.
		if (minecraft.gui.screen() instanceof PauseScreen) {
			minecraft.gui.setScreen(null);
		}

		LocalPlayer player = minecraft.player;
		if (player == null) {
			return;
		}
		if (player.isDeadOrDying()) {
			if (!resync) {
				HostHealth.died(); // not a death from before this link (it would kill the host's player for nothing)
			}
			if (minecraft.gui.screen() != null) {
				minecraft.gui.setScreen(null);
			}
			player.respawn();
			resync = true;
			respawned = true;
			return;
		}
		if (resync) {
			// Just linked, or back from a respawn: stand where the host's player is now, unless the
			// host's own latest move (its level start) was just applied: that already placed us, and
			// its live origin can be a moment stale (once it put Steve outside the map).
			resync = false;
			PlayerCrossings.reset();
			boolean placed = s.teleportSeq() != 0 && teleportAck == s.teleportSeq() && !player.isDeadOrDying() && !respawned;
			LOG.info("PortalCraft: resync: host origin {}, last host move #{} to {}{}", s.origin(), s.teleportSeq(), s.teleportOrigin(),
				placed ? " (already applied: staying)" : "");
			if (!placed) {
				teleport(minecraft, player, Units.toMc(s.origin()), Vec3.ZERO);
			}
			respawned = false;
			teleportAck = s.teleportSeq();
			minecraft.getTutorial().setStep(TutorialSteps.NONE);
			giveGun(minecraft, player);
			freezeDaylight(minecraft);
			player.refreshDimensions(); // host-sized hull (see AvatarDimensionsMixin)
			LOG.info("PortalCraft: player hull {} x {} blocks", player.getBbWidth(), player.getBbHeight());
		}

		carry(player, moved);
		pushOutOfSolids(player);
		pushProps(player);
		if (player.getPose() != lastPose) {
			if (poseLogs++ < 60) {
				LOG.info("PortalCraft: Steve's pose {} -> {} at host {} (sneak key {}, horizontal collision {})", lastPose, player.getPose(),
					Units.toSrc(player.position()), player.isShiftKeyDown(), player.horizontalCollision);
			}
			lastPose = player.getPose();
		}
		// Not in a fast fall (see there), and not while a lift carries him: its platform rises under
		// Steve every tick, each tick read as "feet in the floor", and riding one shook all the way up.
		// (A lift standing still is a floor like any other: left out as well, Steve sank through the
		// one a level starts on and fell out of the map.)
		if (player.getDeltaMovement().y > -0.5 && !s.riding()) {
			liftOutOfTheFloor(player);
		}
		for (String command; (command = HostLink.takeDevCommand()) != null;) {
			runCommand(minecraft, command);
		}
		String devGive = HostLink.takeDevGive();
		if (devGive != null) {
			giveDev(minecraft, player, devGive);
		}
		Vec3 devGoto = HostLink.takeDevGoto();
		if (devGoto != null) {
			teleport(minecraft, player, Units.toMc(devGoto), Vec3.ZERO);
		}

		look(player, s);

		followHostMoves(minecraft, player, s);
		if (System.currentTimeMillis() < holdWithHostUntil && teleportAck == s.teleportSeq() && !s.riding()
			&& player.position().distanceToSqr(holdAt) < 30.0 * 30.0) {
			player.setPos(holdAt);
			player.setDeltaMovement(Vec3.ZERO);
			player.resetFallDistance();
		}
		PortalAir.tick(player);
		PortalAir.funnel(player, s.portals());
		HostEvents.drainHits();
		HostHealth.tick(minecraft, player);
		BlockSolids.tick(minecraft);

		if (s.riding()) {
			// On a moving lift Portal owns Steve's height (it carries its player exactly; our copy of the
			// lift lags): stand at its height, keep walking about, no sag and no fall.
			double hostY = Units.toMc(s.origin()).y;
			if (Math.abs(player.getY() - hostY) < 2.0) {
				player.setPos(player.getX(), hostY, player.getZ());
				Vec3 v = player.getDeltaMovement();
				player.setDeltaMovement(v.x, 0.0, v.z);
				player.resetFallDistance();
			}
		}
		if (s.scripted()) {
			// A scripted scene has Portal's player (its camera, or frozen): stand where it is, take no
			// input, and keep Minecraft's HUD and hand out of Portal's camera (F1) until it's over.
			player.setPos(Units.toMc(s.origin()));
			player.setDeltaMovement(Vec3.ZERO);
			player.resetFallDistance();
			releaseAll(minecraft);
			if (!minecraft.gui.hud.isHidden()) {
				minecraft.gui.hud.toggle();
				hidGuiForScene = true;
			}
			return;
		}
		if (hidGuiForScene) {
			if (minecraft.gui.hud.isHidden()) {
				minecraft.gui.hud.toggle();
			}
			hidGuiForScene = false;
		}
		if (s.foreground()) {
			applyKeys(minecraft, s);
			applyMouse(minecraft, player, s.mouse());
		} else {
			releaseAll(minecraft);
		}
	}

	/** END_CLIENT_TICK: a physics step just finished; send it now so the host can time it. */
	public static void tickEnd(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (!linked || player == null) {
			return;
		}
		Proto.HostState s = HostLink.current();
		if (s != null && s.inGame()) {
			crossPortals(minecraft, player, s);
		}
		tickSeq++;
		tickPrevious = PlayerCrossings.unfold(Units.toSrc(new Vec3(player.xo, player.yo, player.zo)));
		tickCurrent = PlayerCrossings.unfold(Units.toSrc(player.position()));
		sendState(minecraft);
	}

	private static int crossingsLogged;
	private static net.minecraft.world.entity.Pose lastPose;
	private static int poseLogs;

	/** How fast a cube is shoved by a walking Steve, as the speed of the "hit" it takes each tick (blocks/tick). */
	private static final double PROP_PUSH = 0.15;
	private static int pushedProp = -1, pushedStuck;
	private static Vec3 pushedAt = Vec3.ZERO;

	/**
	 * Steve walking into one of the host's movable props shoves it along: a small hit on it every
	 * tick he is stopped against it, the way he pushes it. (Portal's own player does this by
	 * touching it; Steve is kept just clear of it, see LiveEntities.PROP_SKIN.)
	 */
	private static void pushProps(LocalPlayer player) {
		if (!HostCollision.active() || !player.horizontalCollision || (player.xxa == 0.0F && player.zza == 0.0F)) {
			pushedProp = -1;
			return;
		}
		double yaw = Math.toRadians(player.getYRot());
		Vec3 forward = new Vec3(-Math.sin(yaw), 0.0, Math.cos(yaw)), left = new Vec3(Math.cos(yaw), 0.0, Math.sin(yaw));
		Vec3 way = forward.scale(player.zza).add(left.scale(player.xxa));
		if (way.lengthSqr() < 1.0e-6) {
			return;
		}
		way = way.normalize();
		AABB ahead = player.getBoundingBox().move(way.scale(0.15));
		if (!LiveEntities.overlapsProp(ahead)) {
			return;
		}
		// At a cube's own middle height (20 units up when it stands on Steve's floor): higher, it tipped over instead of sliding.
		Vec3 at = player.position().add(0.0, 20.0 / Units.PER_BLOCK, 0.0).add(way.scale(player.getBbWidth() * 0.5 + 0.12));
		// Not one that isn't going anywhere (against a wall): shoved on, it tipped up and Steve
		// climbed it. Four ticks without it moving and he just leans on it, until it moves or he lets up.
		int index = LiveEntities.entityAt(at, 0.15);
		Vec3 now = index < 0 ? null : LiveEntities.originOf(index);
		if (now == null || !LiveEntities.movable(index)) {
			pushedProp = -1;
			return;
		}
		if (index != pushedProp) {
			pushedProp = index;
			pushedStuck = 0;
		} else {
			pushedStuck = now.distanceToSqr(pushedAt) < 0.1 * 0.1 ? pushedStuck + 1 : 0;
		}
		pushedAt = now;
		if (pushedStuck < 6) {
			// Through its middle, wherever Steve touches it: shoved at the corner he had met, a cube
			// turned on the spot and hardly went anywhere.
			HostEvents.hit(Units.toMc(now), way.scale(PROP_PUSH), 0.0F);
		}
	}
	private static int liftLogs;

	/**
	 * Steve with his feet in a floor (a shove from the host put them there, or a prop did): up onto it. Minecraft doesn't stop a body that starts its step inside a shape,
	 * so left there he sinks through the floor and out of the map. Only up, and only a quarter block:
	 * a hull caught in a wall or a ceiling is left for the host to push out. Every tick except in a
	 * fast fall: out of the ceiling portal in testchmb_a_10 it once took something for a floor,
	 * lifted Steve and stopped his infinite fall.
	 */
	private static void liftOutOfTheFloor(LocalPlayer player) {
		if (!HostCollision.active()) {
			return;
		}
		// Only his feet, and only the middle of them: the hull pulled in a tenth of a block from its
		// sides (pressed into a wall by a shove it would never read as clear, and Steve sank through
		// the floor all along the wall), its bottom half block in something and the rest free. (Any
		// overlap at all took Steve coming out of a ceiling portal against its rim, lifted him back
		// up into it and stopped his fall.)
		AABB core = player.getBoundingBox().deflate(0.1, 0.0, 0.1);
		if (core.getYsize() <= 0.6) {
			return;
		}
		AABB feet = new AABB(core.minX, core.minY + 0.005, core.minZ, core.maxX, core.minY + 0.5, core.maxZ);
		AABB rest = new AABB(core.minX, core.minY + 0.5, core.minZ, core.maxX, core.maxY - 0.02, core.maxZ);
		if (player.level().noCollision(player, feet) || !player.level().noCollision(player, rest)) {
			return;
		}
		// Not off one of the host's fixtures that stands beside him: a security camera turns to watch
		// the player, its body swept under Steve as he dropped out of the ceiling portal next to it,
		// and "up onto the floor" put him back in the portal. (A lift's platform is a floor: with every
		// fixture left out, Steve sank through the lift at the level's start.)
		if (LiveEntities.besideFixture(feet)) {
			return;
		}
		// A cube he is standing on that has come up a hair under his soles (it settles and jiggles
		// under his weight): back onto its top. Left alone he sank through it in a few seconds.
		double propTop = LiveEntities.propTopUnder(feet);
		if (!Double.isNaN(propTop)) {
			player.setPos(player.getX(), propTop + 0.005, player.getZ());
			if (player.getDeltaMovement().y < 0.0) {
				player.setDeltaMovement(player.getDeltaMovement().multiply(1.0, 0.0, 1.0));
			}
			return;
		}
		// Not off the side of one, though: a cube against his shins read as a floor half a block up,
		// tick after tick, and Steve climbed it. (pushOutOfSolids moves him out of a prop, sideways first.)
		if (LiveEntities.overlapsProp(feet.inflate(0.15, 0.0, 0.15))) {
			return;
		}
		// A quarter of a block at most: sinking is caught within a tick or two, a unit or so deep. More
		// than that is something he has come down beside (a fitting under a ceiling), not a floor.
		for (int i = 1; i <= 16; i++) {
			double up = i / 64.0;
			if (player.level().noCollision(player, new AABB(core.minX, core.minY + up + 0.005, core.minZ, core.maxX, core.maxY + up - 0.02, core.maxZ))) {
				if (liftLogs++ < 60) {
					StringBuilder what = new StringBuilder();
					for (net.minecraft.world.phys.shapes.VoxelShape shape : player.level().getBlockCollisions(player, feet)) {
						AABB hit = shape.bounds();
						what.append(' ').append(Units.toSrc(new Vec3(hit.minX, hit.minY, hit.minZ))).append("..").append(Units.toSrc(new Vec3(hit.maxX, hit.maxY, hit.maxZ)));
					}
					LOG.info("PortalCraft: Steve's feet were in the floor at {} (host {}): lifted {} blocks; the floor:{}", player.position(),
						Units.toSrc(player.position()), up, what);
				}
				player.setPos(player.position().add(0.0, up, 0.0));
				if (player.getDeltaMovement().y < 0.0) {
					player.setDeltaMovement(player.getDeltaMovement().multiply(1.0, 0.0, 1.0));
				}
				return;
			}
		}
	}

	/**
	 * `feet`, or the nearest place up to a quarter block higher where Steve's hull touches nothing.
	 * A body that starts a step inside a floor isn't stopped by it: out of a portal a hair low,
	 * Steve sank through the floor and out of the map.
	 */
	private static Vec3 clearOfTheFloor(LocalPlayer player, Vec3 feet) {
		return clearOfTheFloor(player, feet, 16);
	}

	/** As above, looking up to `steps` 64ths of a block higher. Returns `feet` itself if nowhere is clear. */
	private static Vec3 clearOfTheFloor(LocalPlayer player, Vec3 feet, int steps) {
		for (int i = 0; i <= steps; i++) {
			Vec3 at = i == 0 ? feet : feet.add(0.0, i / 64.0, 0.0);
			if (player.level().noCollision(player, player.getBoundingBox().move(at.subtract(player.position())).deflate(0.001))) {
				return at;
			}
		}
		return feet;
	}

	/**
	 * The step that just ended took Steve's centre in through a linked host portal: out of the
	 * other one, now (PlayerCrossings). Not while the host has a move of its own on the way to us,
	 * on a lift, or in a scripted scene: then the host has the player.
	 */
	private static void crossPortals(Minecraft minecraft, LocalPlayer player, Proto.HostState s) {
		if (!HostCollision.active() || s.scripted() || s.riding() || s.teleportSeq() != teleportAck
			|| Units.offsetX() != dev.portalcraft.host.MapRegions.offsetX(s.map())) {
			return;
		}
		double halfHeight = player.getBbHeight() * 0.5 * Units.PER_BLOCK;
		PlayerCrossings.Carried c = PlayerCrossings.step(s.portals(), Units.toSrc(new Vec3(player.xo, player.yo, player.zo)),
			Units.toSrc(player.position()), Units.velocityToSrc(player.getDeltaMovement()), halfHeight, player.getBbWidth() * 0.5 * Units.PER_BLOCK);
		if (c == null) {
			return;
		}
		// The rest of the step after the portal, swept against the world from where he came out. At
		// speed that is up to a block and a half: out of a portal facing a wall close by, Steve was
		// put inside the wall, or beyond it.
		Vec3 exitAt = Units.toMc(c.exitFeet()), carriedTo = Units.toMc(c.feet());
		Vec3 rest = carriedTo.subtract(exitAt);
		if (rest.lengthSqr() > 1.0e-6) {
			AABB atExit = player.getBoundingBox().move(exitAt.subtract(player.position()));
			carriedTo = exitAt.add(net.minecraft.world.entity.Entity.collideBoundingBox(player, rest, atExit, player.level(), List.of()));
		}
		Vec3 feet = clearOfTheFloor(player, carriedTo), before = Units.toMc(c.previousFeet());
		Vec3 velocity = Units.velocityToMc(c.velocity());
		player.setPos(feet);
		// Last tick's place carried through as well, so this step reads as the same smooth move.
		player.xo = player.xOld = before.x;
		player.yo = player.yOld = before.y;
		player.zo = player.zOld = before.z;
		player.setDeltaMovement(velocity);
		player.resetFallDistance();
		if (velocity.lengthSqr() > 0.1 * 0.1) {
			PortalAir.startFling();
		}
		var server = minecraft.getSingleplayerServer();
		if (server != null) {
			var uuid = player.getUUID();
			server.execute(() -> {
				ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
				if (sp != null) {
					// (The server takes the jump from the client's own move: LivingEntityAirMixin switches
					// off its "moved wrongly" check inside a host map. Placing its player here as well raced
					// with the moves already on their way to it.)
					sp.resetFallDistance();
				}
			});
		}
		if (crossingsLogged++ < 40) {
			LOG.info("PortalCraft: Steve went through {} host portal(s) (#{}): out at {} (host {}, moved {} to fit) with velocity {}", c.crossings(),
				PlayerCrossings.count(), feet, c.feet(), c.fit(), velocity);
		}
	}

	/** Once per render frame: tell the host where Minecraft's player is. */
	public static void frame(Minecraft minecraft) {
		if (!linked) {
			return;
		}
		// The host's moves as soon as they arrive, not at the next tick: while it waits for our
		// answer it can't drive the player (a real teleport) or has to carry a shove itself.
		Proto.HostState hs = HostLink.current();
		if (hs != null) {
			PlayerCrossings.matched(hs.crossMatched());
		}
		// Only once the tick has moved to the host's map's region: a level start applied before that
		// would land Steve in the last map's stretch of the world (and the void).
		if (hs != null && minecraft.player != null && hs.inGame() && Units.offsetX() == dev.portalcraft.host.MapRegions.offsetX(hs.map())) {
			followHostMoves(minecraft, minecraft.player, hs);
		}
		if (hs != null && minecraft.player != null && hs.inGame()) {
			look(minecraft.player, hs); // every frame, as the mouse turns a player
		}
		boolean ready = sendState(minecraft);
		moveCursor(minecraft);
		scroll(minecraft);
		type(minecraft);

		// The placed blocks, for the host to draw in its own 3D pass.
		if (ready && WorldLink.open()) {
			WorldExporter.frame(minecraft);
		}
	}

	private static boolean sendState(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		boolean ready = player != null && minecraft.level != null;
		Vec3 pos = Vec3.ZERO, vel = Vec3.ZERO;
		if (ready) {
			float partial = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true);
			// Unfolded: as if Steve hadn't yet made the portal crossings the host hasn't (PlayerCrossings).
			pos = PlayerCrossings.unfold(Units.toSrc(player.getPosition(partial)));
			// Real motion this tick. deltaMovement is already scaled down by ground friction, which
			// would hand the host about half the walking speed (and weak flings through portals).
			vel = PlayerCrossings.unfoldDir(Units.velocityToSrc(player.position().subtract(player.xo, player.yo, player.zo)));
		}
		byte[] crossPortal = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
		for (int k = Math.max(1, PlayerCrossings.count() - 3); k <= PlayerCrossings.count(); k++) {
			crossPortal[k & 3] = (byte) PlayerCrossings.portalOf(k);
		}
		int flags = (ready ? Proto.MC_READY : 0) | (screenWantsCursor(minecraft) ? Proto.MC_SCREEN : 0) | (ready && player.isSprinting() ? Proto.MC_SPRINT : 0)
			| (ready && player.isFallFlying() ? Proto.MC_GLIDING : 0);
		HostLink.send(Proto.writeMcState(++seq, flags, teleportAck, pos, vel,
			ready && player.onGround(), ready && player.isShiftKeyDown(), ready && player.getMainHandItem().is(PortalCraft.PORTAL_GUN), cameraMode(minecraft),
			tickPrevious, tickCurrent, tickSeq, ready ? cameraDistance(minecraft, player) : 0.0F, PlayerCrossings.count(), PlayerCrossings.matched(),
			crossPortal));
		return ready;
	}

	/**
	 * A screen the player points at (inventory, chests, crafting, chat): the host lets go of its
	 * mouse while one is open and sends its cursor. Not the pause screen, which tick() closes.
	 */
	private static boolean screenWantsCursor(Minecraft minecraft) {
		Screen screen = minecraft.gui.screen();
		return screen != null && !(screen instanceof PauseScreen);
	}

	private static double cursorX = -1.0, cursorY = -1.0;

	/** Characters typed into the host's window (chat, signs, books, search boxes). */
	private static void type(Minecraft minecraft) {
		for (Integer codepoint; (codepoint = HostLink.takeTyped()) != null;) {
			if (minecraft.gui.screen() != null) {
				minecraft.keyboardHandler.charTyped(minecraft.getWindow().handle(), new CharacterEvent(codepoint));
			}
		}
	}
	private static int lastWheel = Integer.MIN_VALUE;

	/**
	 * The host's mouse wheel (a wrapping notch count): each change scrolls Minecraft, which turns
	 * the hotbar in game and scrolls lists in screens.
	 */
	private static void scroll(Minecraft minecraft) {
		Proto.HostState s = HostLink.current();
		if (s == null) {
			return;
		}
		int wheel = s.wheel();
		if (lastWheel == Integer.MIN_VALUE) {
			lastWheel = wheel; // the first state: no movement yet
			return;
		}
		int notches = (byte) (wheel - lastWheel);
		lastWheel = wheel;
		if (notches != 0 && minecraft.player != null && (s.foreground() || screenWantsCursor(minecraft))) {
			minecraft.mouseHandler.onScroll(minecraft.getWindow().handle(), 0.0, notches);
		}
	}

	/**
	 * Every render frame while a screen is open: the host's cursor (0..1 over its window) becomes
	 * Minecraft's, in window coordinates, so items under it highlight and clicks land on it.
	 */
	private static void moveCursor(Minecraft minecraft) {
		Proto.HostState s = HostLink.current();
		if (s == null || !screenWantsCursor(minecraft) || s.cursorX() < 0.0F || s.cursorY() < 0.0F) {
			cursorX = cursorY = -1.0;
			return;
		}
		var window = minecraft.getWindow();
		double x = s.cursorX() * window.getScreenWidth(), y = s.cursorY() * window.getScreenHeight();
		if (x == cursorX && y == cursorY) {
			return;
		}
		double dx = cursorX < 0.0 ? 0.0 : x - cursorX, dy = cursorY < 0.0 ? 0.0 : y - cursorY;
		cursorX = x;
		cursorY = y;
		minecraft.mouseHandler.onMove(window.handle(), x, y, dx, dy);
	}

	/** Minecraft's third-person distance, before blocks or walls get in the way (Camera.setup). */
	private static final float CAMERA_DISTANCE = 4.0F;

	/**
	 * McState.cameraDistance: how far back (or, in front view, forward) the third-person camera
	 * gets before something stops it, in host units: Camera.getMaxZoom's eight jittered clips,
	 * which see Steve's blocks and (ClipContextMixin) the host's walls.
	 */
	private static float cameraDistance(Minecraft minecraft, LocalPlayer player) {
		CameraType type = minecraft.options.getCameraType();
		if (type.isFirstPerson() || minecraft.level == null) {
			return 0.0F;
		}
		float partial = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		Vec3 eye = player.getEyePosition(partial);
		double reach = CAMERA_DISTANCE * dev.portalcraft.host.HostScale.STEVE; // Minecraft's own grows with his scale
		Vec3 dir = player.getViewVector(partial).scale(type.isMirrored() ? reach : -reach);
		double best = reach;
		for (int i = 0; i < 8; i++) {
			Vec3 from = eye.add(((i & 1) * 2 - 1) * 0.1, ((i >> 1 & 1) * 2 - 1) * 0.1, ((i >> 2 & 1) * 2 - 1) * 0.1);
			HitResult hit = minecraft.level.clip(new ClipContext(from, from.add(dir), ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player));
			if (hit.getType() != HitResult.Type.MISS) {
				best = Math.min(best, hit.getLocation().distanceTo(eye));
			}
		}
		return (float) (best * Units.PER_BLOCK);
	}

	/** McState.cameraMode: 0 first person, 1 third person behind (F5), 2 third person in front. */
	private static int cameraMode(Minecraft minecraft) {
		return switch (minecraft.options.getCameraType()) {
			case FIRST_PERSON -> 0;
			case THIRD_PERSON_BACK -> 1;
			case THIRD_PERSON_FRONT -> 2;
		};
	}

	/**
	 * Standing on something the host moved (a lift, a moving panel, a button going down): move
	 * with it, the way the host would carry its own player.
	 */
	/** How far, and which ways, to look for free space when Steve is inside something (blocks). */
	private static final double[] PUSH_STEPS = {0.05, 0.1, 0.2, 0.3, 0.45, 0.6, 0.8};
	private static final double[][] PUSH_WAYS = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0.7071, 0, 0.7071}, {0.7071, 0, -0.7071},
		{-0.7071, 0, 0.7071}, {-0.7071, 0, -0.7071}, {0, 1, 0}};

	private static int pushLogs;

	/**
	 * Steve inside one of the host's loose props (a cube Portal's physics slid into him): out the
	 * shortest way. Minecraft never does this itself: a body already inside a shape moves through
	 * it freely, which is how Steve could sprint into a cube he had just shoved. Only props: used
	 * on anything solid, it took Steve coming out of a ceiling portal a little off-centre (his
	 * shoulder in the ceiling beside it) and pushed him up, back through the portal.
	 */
	private static void pushOutOfSolids(LocalPlayer player) {
		AABB body = player.getBoundingBox().deflate(0.02);
		if (!LiveEntities.overlapsProp(body) || player.level().noCollision(player, body)) {
			return;
		}
		for (double step : PUSH_STEPS) {
			for (double[] way : PUSH_WAYS) {
				Vec3 by = new Vec3(way[0] * step, way[1] * step, way[2] * step);
				if (player.level().noCollision(player, body.move(by))) {
					if (pushLogs++ < 30) {
						LOG.info("PortalCraft: Steve was inside a prop at {} (host {}): pushed by {}", player.position(), Units.toSrc(player.position()), by);
					}
					player.setPos(player.position().add(by));
					return;
				}
			}
		}
	}

	private static void carry(LocalPlayer player, List<LiveEntities.Moved> moved) {
		if (moved.isEmpty()) {
			return;
		}
		AABB feet = player.getBoundingBox();
		feet = new AABB(feet.minX, feet.minY - 0.2, feet.minZ, feet.maxX, feet.minY + 0.1, feet.maxZ);
		for (LiveEntities.Moved m : moved) {
			if (m.before().intersects(feet) && m.delta().lengthSqr() < 4.0) {
				player.setPos(player.position().add(m.delta()));
				return;
			}
		}
	}

	/** Dev: give a full stack of an item by id (tools/fake_mc.py --give minecraft:stone). */
	private static void giveDev(Minecraft minecraft, LocalPlayer player, String id) {
		var server = minecraft.getSingleplayerServer();
		var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(net.minecraft.resources.Identifier.tryParse(id));
		if (server == null || item.isEmpty()) {
			LOG.warn("PortalCraft: dev give: no item {}", id);
			return;
		}
		var uuid = player.getUUID();
		server.execute(() -> {
			ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
			if (sp != null) {
				sp.getInventory().add(new ItemStack(item.get(), item.get().getDefaultMaxStackSize()));
				LOG.info("PortalCraft: dev give {}", id);
			}
		});
	}

	/**
	 * Everything Minecraft draws for the host (the hand, held and dropped items) is lit by its
	 * own sky. A void world drifting into night turns all of it near-black inside a bright test
	 * chamber, so pin it to clear noon.
	 */
	/** Dev: a Minecraft command, run as the server (full permissions), e.g. a /give with components. */
	private static void runCommand(Minecraft minecraft, String command) {
		var server = minecraft.getSingleplayerServer();
		if (server == null) {
			return;
		}
		LOG.info("PortalCraft: dev command: {}", command);
		if (command.equals("portalcraft:save")) { // single player has no save-all: save the world and players now
			server.execute(() -> LOG.info("PortalCraft: saved: {}", server.saveEverything(false, true, true)));
			return;
		}
		server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command));
	}

	/**
	 * The void world is a stage, not a survival map: daylight and weather are pinned (so what Steve
	 * holds is lit), deaths keep the inventory (a fall out of a host map is a long one), fall
	 * damage stays on (Steve has no long-fall boots: water-bucket clutch, see chamberKit), and
	 * commands are allowed, as "Open to LAN, allow cheats" would.
	 */
	private static void freezeDaylight(Minecraft minecraft) {
		var server = minecraft.getSingleplayerServer();
		if (server == null) {
			return;
		}
		server.execute(() -> {
			if (!server.getWorldData().isAllowCommands()) {
				server.setWorldAllowCommands(true);
				LOG.info("PortalCraft: commands allowed in this world");
			}
			var source = server.createCommandSourceStack().withSuppressedOutput();
			for (String command : new String[] {"gamerule advance_time false", "time set noon", "gamerule advance_weather false", "weather clear",
				"gamerule keep_inventory true", "gamerule fall_damage true"}) {
				server.getCommands().performPrefixedCommand(source, command);
			}
		});
	}

	/** Steve starts with the portal gun; while he holds it, clicks fire the host's real portals. */
	private static void giveGun(Minecraft minecraft, LocalPlayer player) {
		var server = minecraft.getSingleplayerServer();
		if (server == null) {
			return;
		}
		var uuid = player.getUUID();
		server.execute(() -> {
			ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
			if (sp != null && !sp.getInventory().contains(new ItemStack(PortalCraft.PORTAL_GUN))) {
				sp.getInventory().add(new ItemStack(PortalCraft.PORTAL_GUN));
			}
		});
	}

	/** Render the overlay at the host's resolution so the HUD is sharp and the right size. */
	private static void matchHostWindowSize(Minecraft minecraft) {
		int w = OverlayLink.hostWidth(), h = OverlayLink.hostHeight();
		var window = minecraft.getWindow();
		if (w <= 0 || h <= 0 || w > OverlayLink.MAX_W || h > OverlayLink.MAX_H || window.isExclusiveFullscreen()) {
			return;
		}
		if (window.getWidth() != w || window.getHeight() != h) {
			LOG.info("PortalCraft: matching the host's {}x{}", w, h);
			window.setWindowed(w, h);
		}
	}

	private static void loadMap(String name) {
		if (name.equals(HostCollision.mapName())) {
			return;
		}
		// Its own stretch of the Minecraft world (MapRegions), before anything converts a position.
		double offset = dev.portalcraft.host.MapRegions.offsetX(name);
		if (Units.offsetX() != offset) {
			Units.setOffsetX(offset);
			LOG.info("PortalCraft: {} is at x {} in the Minecraft world", name, (long) offset);
		}
		mapLoadedAt = System.currentTimeMillis();
		Path file = maps().resolve(name + ".bsp");
		if (file.equals(failedMap)) {
			return;
		}
		try {
			long t0 = System.nanoTime();
			BspMap map = BspMap.load(file, name);
			HostCollision.setMap(map);
			if (Minecraft.getInstance().player != null) {
				Minecraft.getInstance().player.refreshDimensions(); // host-sized hull
			}
			LOG.info("PortalCraft: loaded {} ({} solid brushes) in {} ms", file, map.brushes.size(), (System.nanoTime() - t0) / 1_000_000);
			chamberKit(name);
		} catch (Exception e) {
			failedMap = file;
			HostCollision.clear();
			LOG.warn("PortalCraft: can't read {} ({}). Set -Dportalcraft.mapsDir to the game's maps folder.", file, e.toString());
		}
	}

	/**
	 * Chell's long-fall boots aren't Steve's: fall damage is on, so every test chamber and escape
	 * level hands him a water bucket to clutch a fall with (portals make a long drop out of any
	 * chamber), unless he already carries one.
	 */
	private static void chamberKit(String map) {
		if (!map.startsWith("testchmb_") && !map.startsWith("escape_")) {
			return;
		}
		var server = Minecraft.getInstance().getSingleplayerServer();
		if (server == null) {
			return;
		}
		server.execute(() -> {
			for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
				if (!sp.getInventory().contains(stack -> stack.is(net.minecraft.world.item.Items.WATER_BUCKET))) {
					sp.getInventory().add(new ItemStack(net.minecraft.world.item.Items.WATER_BUCKET));
					LOG.info("PortalCraft: {}: gave {} a water bucket for the falls", map, sp.getName().getString());
				}
			}
		});
	}

	/** Where each host portal last was ([0] blue, [1] orange), to see which one was just placed. */
	private static final Vec3[] lastPortalAt = new Vec3[2];

	/**
	 * Portal's gun lights up in the colour of the portal it last fired. The host places its own
	 * portals, so whichever of them just appeared or moved sets the Minecraft gun's light.
	 */
	private static int lastShots = -1;
	private static long lastShotAt;

	/** The host's gun fired (HostState.shots changed): Steve's flashes and plays its firing animation. */
	private static void gunFollowsHostShots(Minecraft minecraft, int shots) {
		if (lastShots < 0 || shots == lastShots) {
			lastShots = shots;
			return;
		}
		lastShots = shots;
		lastShotAt = System.currentTimeMillis();
		dev.portalcraft.client.gun.GunAnimation.shot();
		showShot(minecraft, (shots & 0x80) != 0 ? dev.portalcraft.PortalColor.SECONDARY : dev.portalcraft.PortalColor.PRIMARY);
	}

	private static void showShot(Minecraft minecraft, dev.portalcraft.PortalColor color) {
		var server = minecraft.getSingleplayerServer();
		if (server == null || minecraft.player == null) {
			return;
		}
		var uuid = minecraft.player.getUUID();
		server.execute(() -> {
			ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
			if (sp != null && sp.getMainHandItem().is(PortalCraft.PORTAL_GUN)) {
				dev.portalcraft.PortalGunItem.setLastFired(sp, sp.getMainHandItem(), color);
			}
		});
	}

	private static void lightFollowsHostPortals(Minecraft minecraft, Proto.HostPortal[] portals) {
		for (int i = 0; i < Math.min(2, portals.length); i++) {
			Proto.HostPortal p = portals[i];
			Vec3 at = p != null && (p.flags() & Proto.PORTAL_ACTIVE) != 0 ? p.origin() : null;
			boolean placed = at != null && (lastPortalAt[i] == null || lastPortalAt[i].distanceToSqr(at) > 1.0);
			lastPortalAt[i] = at;
			// A portal landed with no shot seen for it (a host that doesn't report its shots): the gun
			// shows it then. With the shot reported, the gun has already flashed.
			if (placed && System.currentTimeMillis() - lastShotAt > 1500) {
				showShot(minecraft, i == 0 ? dev.portalcraft.PortalColor.PRIMARY : dev.portalcraft.PortalColor.SECONDARY);
			}
		}
	}

	/**
	 * Portal's view angles, applied the way Minecraft's own mouse turns the player: every frame, by
	 * a step that also moves the previous-tick angles (yRotO, xRotO), with the yaw kept continuous
	 * rather than wrapped to -180..180. Set once a tick as absolute angles (as this used to be),
	 * the rendered view stepped 20 times a second while the hand's sway (which eases after the view)
	 * moved smoothly, so the gap between them jumped and the hand skipped while turning; crossing
	 * 180 degrees swung the interpolation the long way round.
	 */
	private static void look(LocalPlayer player, Proto.HostState s) {
		float dy = net.minecraft.util.Mth.wrapDegrees(Units.yawToMc(s.yaw()) - player.getYRot());
		float dx = s.pitch() - player.getXRot();
		player.setYRot(player.getYRot() + dy);
		player.setXRot(s.pitch());
		player.yRotO += dy;
		player.xRotO += dx;
		player.setYHeadRot(player.getYRot());
		player.yHeadRotO += dy;
	}

	private static void followHostMoves(Minecraft minecraft, LocalPlayer player, Proto.HostState s) {
		if (s.teleportSeq() == 0) {
			teleportAck = 0; // a fresh host session that hasn't placed the player yet
		} else if (s.teleportSeq() != teleportAck) {
			Vec3 to = Units.toMc(s.teleportOrigin());
			if (s.teleportKind() == Proto.MOVE_IMPULSE) {
				// An air current or an explosion: Portal's velocity is the point.
				player.setPos(to);
				player.setDeltaMovement(Units.velocityToMc(s.teleportVelocity()));
				player.resetFallDistance();
				PortalAir.startFling();
			} else if (s.teleportKind() == Proto.MOVE_SHOVE) {
				// A shove (a prop, the host's physics settling its player): an offset, on top of wherever
				// Steve is now, with his own momentum kept. HostState.teleportVelocity carries it (units).
				if (s.moveBase() != teleportAck) {
					return; // summed from an older answer of ours: the next state has the right sum
				}
				Vec3 by = s.teleportVelocity();
				if (by.lengthSqr() < 64.0 * 64.0) {
					player.setPos(player.position().add(by.x / Units.PER_BLOCK, by.z / Units.PER_BLOCK, -by.y / Units.PER_BLOCK));
					liftOutOfTheFloor(player); // a shove can leave his feet in the floor
				}
			} else if (s.crossing() != null && s.teleportKind() == Proto.MOVE_TELEPORT) {
				// Portal crossings: carry where Steve is now (and how fast) through them. Jumping to
				// teleportOrigin instead set him back the tick or two the move took to arrive, and the
				// host, playing Minecraft's steps back, saw him stall and jump at every portal.
				Proto.Crossing c = s.crossing();
				if (c.base() != teleportAck) {
					return; // composed from an older answer of ours: the next state has the right one
				}
				if (PlayerCrossings.count() > PlayerCrossings.matched()) {
					// Steve went through himself just before this arrived (PlayerCrossings): it's the same
					// crossing. He's already out; stop unfolding it and take the move as done.
					PlayerCrossings.forgetPending();
				} else {
					Vec3 out = c.point(Units.toSrc(player.position()));
					Vec3 pos = Units.toMc(out);
					Vec3 carried = c.dir(Units.velocityToSrc(player.getDeltaMovement()));
					// Portal's exit rules (a floor portal throws you clear; nothing leaves faster than 1000),
					// for the portal he came out of: the nearer one.
					Proto.HostPortal[] ps = s.portals();
					if (ps.length >= 2 && ps[0] != null && ps[1] != null) {
						Proto.HostPortal exit = ps[0].origin().distanceToSqr(out) <= ps[1].origin().distanceToSqr(out) ? ps[0] : ps[1];
						carried = PlayerCrossings.exitVelocity(exit, carried);
					}
					Vec3 velocity = Units.velocityToMc(carried);
					teleport(minecraft, player, pos, velocity);
					if (velocity.lengthSqr() > 0.1 * 0.1) {
						PortalAir.startFling();
					}
				}
			} else {
				PlayerCrossings.forgetPending(); // the host placed him: nothing of ours left to unfold
				if (System.currentTimeMillis() - mapLoadedAt < 20000) {
					holdWithHostUntil = System.currentTimeMillis() + 2500;
					holdAt = to;
				}
				Vec3 velocity = Units.velocityToMc(s.teleportVelocity());
				teleport(minecraft, player, to, velocity);
				// Out of a portal with speed: Portal's flight until he lands. (Not a level start, even one
				// that arrives moving, as the elevator's does.)
				if (velocity.lengthSqr() > 0.1 * 0.1 && System.currentTimeMillis() - mapLoadedAt > 3000) {
					PortalAir.startFling();
				}
			}
			teleportAck = s.teleportSeq();
		}
	}

	private static void teleport(Minecraft minecraft, LocalPlayer player, Vec3 pos, Vec3 velocity) {
		boolean far = player.position().distanceToSqr(pos) > 0.25;
		player.setPos(pos);
		player.setDeltaMovement(velocity);
		player.resetFallDistance();
		var server = minecraft.getSingleplayerServer();
		if (server != null) {
			var uuid = player.getUUID();
			server.execute(() -> {
				ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
				if (sp != null) {
					sp.teleport(new TeleportTransition(sp.level(), pos, velocity, sp.getYRot(), sp.getXRot(), TeleportTransition.DO_NOTHING));
					sp.resetFallDistance();
				}
			});
		}
		if (far) {
			LOG.info("PortalCraft: host moved the player to {} (velocity {})", pos, velocity);
		}
	}

	private static final int SC_E = 8, SC_TAB = 43, SC_ESCAPE = 41;

	/**
	 * The host's keys as Minecraft should see them. E is Portal's "use" (grab cubes, press
	 * buttons), so Minecraft never gets it; Tab, which Portal leaves alone, is Minecraft's E
	 * (the inventory) instead.
	 */
	private static boolean mapped(Proto.HostState s, int scancode) {
		if (scancode == SC_E) {
			return s.keyDown(SC_TAB);
		}
		if (scancode == SC_TAB) {
			return false;
		}
		if (scancode == SC_ESCAPE) {
			// Esc closes Minecraft's screens (the host keeps its keys while one is open); with none
			// open it's the host's pause menu, and Minecraft's would just get in the way.
			return s.keyDown(SC_ESCAPE) && Minecraft.getInstance().gui.screen() != null;
		}
		return s.keyDown(scancode);
	}

	private static void applyKeys(Minecraft minecraft, Proto.HostState s) {
		for (int sc = 1; sc < KEYS.length; sc++) {
			boolean down = mapped(s, sc);
			if (down != KEYS[sc]) {
				KEYS[sc] = down;
				press(minecraft, sc, down);
			}
		}
	}

	/**
	 * Mouse clicks are Minecraft's (attack, use, pick block) unless Steve holds the portal gun: the
	 * host fires its own gun then, so Minecraft gets none and lets go of any it had down. With a
	 * screen open every click is the screen's, at the host's cursor (moveCursor).
	 */
	private static void applyMouse(Minecraft minecraft, LocalPlayer player, int wanted) {
		boolean screen = minecraft.gui.screen() != null;
		if (screen) {
			if (cursorX < 0.0) {
				wanted &= buttons; // no cursor from the host (yet): let go, but don't click blind
			}
		} else if (player.getMainHandItem().is(PortalCraft.PORTAL_GUN)) {
			wanted = 0;
		}
		for (int bit = 0; bit < MOUSE_BUTTONS.length; bit++) {
			int mask = 1 << bit;
			boolean down = (wanted & mask) != 0;
			if (down != ((buttons & mask) != 0)) {
				if (down && !screen) {
					readyToClick(minecraft);
				}
				buttons ^= mask;
				click(minecraft, MOUSE_BUTTONS[bit], down);
			}
		}
	}

	/**
	 * Vanilla's MouseHandler.onButton grabs an ungrabbed mouse on the first click, and grabMouse sets
	 * missTime to 10000, so that click's attack does nothing; a held attack also only keeps
	 * breaking while the mouse is grabbed. InputConstantsMixin leaves the OS cursor alone while
	 * linked, so grab Minecraft's side up front and drop the grab's cooldown. Vanilla's own
	 * 10-tick cooldown after a survival miss is kept.
	 */
	private static void readyToClick(Minecraft minecraft) {
		if (!minecraft.mouseHandler.isMouseGrabbed()) {
			minecraft.mouseHandler.grabMouse();
		}
		if (minecraft.missTime > 10) {
			minecraft.missTime = 0;
		}
	}

	private static void click(Minecraft minecraft, int button, boolean down) {
		minecraft.mouseHandler.onButton(minecraft.getWindow().handle(), new MouseButtonInfo(button, modifiers()), down ? 1 : 0);
	}

	private static void releaseAll(Minecraft minecraft) {
		for (int sc = 1; sc < KEYS.length; sc++) {
			if (KEYS[sc]) {
				KEYS[sc] = false;
				press(minecraft, sc, false);
			}
		}
		for (int bit = 0; bit < MOUSE_BUTTONS.length; bit++) {
			if ((buttons & (1 << bit)) != 0) {
				buttons &= ~(1 << bit);
				click(minecraft, MOUSE_BUTTONS[bit], false);
			}
		}
	}

	private static int modifiers() {
		int mods = 0;
		if (KEYS[225]) mods |= 0x0001; // SDL_KMOD_LSHIFT
		if (KEYS[229]) mods |= 0x0002; // SDL_KMOD_RSHIFT
		if (KEYS[224]) mods |= 0x0040; // SDL_KMOD_LCTRL
		if (KEYS[228]) mods |= 0x0080; // SDL_KMOD_RCTRL
		if (KEYS[226]) mods |= 0x0100; // SDL_KMOD_LALT
		return mods;
	}

	private static void press(Minecraft minecraft, int scancode, boolean down) {
		int mods = modifiers();
		int keycode = SDLKeyboard.SDL_GetKeyFromScancode(scancode, (short) mods, true);
		minecraft.keyboardHandler.keyPress(minecraft.getWindow().handle(), down ? 1 : 0, new KeyEvent(scancode, keycode, mods));
	}
}
