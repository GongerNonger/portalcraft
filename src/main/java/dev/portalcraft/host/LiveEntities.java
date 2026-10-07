package dev.portalcraft.host;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.portalcraft.PortalCraft;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Collision for the host's live solid entities: chamber doors, floor buttons, lifts, cubes and
 * walls that switch on and off. Each packet says what is solid now and where; an entity's brushes
 * are rebuilt only when it moved, and its model's collision is read once per map.
 *
 * Shapes by solid type: BSP = the map's brush model "*N" placed at the entity; VPHYSICS = the
 * model's .phy hulls (or its hull box); BBOX = the world-aligned box; OBB = the box rotated.
 */
public final class LiveEntities {
	private static final Logger LOG = LoggerFactory.getLogger(PortalCraft.MOD_ID);
	private static final int SOLID_BSP = 1, SOLID_BBOX = 2, SOLID_OBB = 3, SOLID_OBB_YAW = 4, SOLID_VPHYSICS = 6;
	private static final Vec3[] WORLD_AXES = {new Vec3(1, 0, 0), new Vec3(0, -1, 0), new Vec3(0, 0, 1)};

	/** An entity as placed last time: its pose, its brushes, and the box around them (blocks). */
	public record Placed(Proto.HostEntity pose, List<BspMap.Brush> brushes, @Nullable AABB bounds, boolean carried) {
	}

	/** Where the player is while the host's gun holds an object (host units), else null. */
	private static volatile @Nullable Vec3 carrying;
	/** A prop this close to the player while the gun holds one is the one it holds (units). */
	private static final double CARRY_REACH = 110.0;

	/**
	 * The host's gun holds an object (`player` is where its player stands) or not (null). Portal
	 * turns off collision between its player and what they carry; without the same here, Steve was
	 * blocked by the cube in his own hands, and climbed onto it when he looked down.
	 */
	public static void carrying(@Nullable Vec3 player) {
		carrying = player;
	}

	private static final Map<Integer, Placed> PLACED = new HashMap<>();

	/**
	 * A movable host prop, for Minecraft's pressure plates (HostPlates): its bounds (Minecraft
	 * coordinates) and whether it counts as a mob (a turret: stone plates take mobs only).
	 */
	public record Prop(AABB bounds, boolean mob) {
	}

	/** The movable props, for the server thread; replaced whole whenever the host's entities change. */
	private static volatile List<Prop> props = List.of();

	public static List<Prop> props() {
		return props;
	}

	/** True if `body` (Minecraft coordinates) overlaps a movable prop's collision: a cube, a turret, the radio. */
	public static boolean overlapsProp(AABB body) {
		return overlaps(body, true);
	}

	/**
	 * True if `feet` (the bottom slab of a hull) is in one of the host's other solid entities that
	 * stands higher than the slab: a security camera, a door. Not a floor to be lifted onto, where a
	 * lift's platform, level with the feet, is.
	 */
	public static boolean besideFixture(AABB feet) {
		// A little wider than asked: a tilted hull's collision is built of columns that reach up to a
		// column's width past the hull itself, so the camera's can touch the slab where its box doesn't.
		feet = feet.inflate(0.15, 0.0, 0.15);
		for (Placed p : PLACED.values()) {
			if (p.bounds() == null || movableProp(p.pose().model()) || !p.bounds().intersects(feet)) {
				continue;
			}
			for (BspMap.Brush brush : p.brushes()) {
				AABB box = brush.mcBox();
				// (All of a security camera: it is nine small pieces, and the one under Steve's feet
				// as it turns can be a low one.)
				if (box.intersects(feet) && (box.maxY > feet.maxY || p.pose().model().contains("security_camera"))) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * The top (Minecraft y) of a movable prop that `feet`, the bottom slab of a hull, has sunk a
	 * little way into from above: the cube Steve is standing on. NaN if there is none.
	 */
	public static double propTopUnder(AABB feet) {
		double top = Double.NaN;
		for (Placed p : PLACED.values()) {
			if (p.bounds() == null || !movableProp(p.pose().model()) || !p.bounds().intersects(feet)) {
				continue;
			}
			for (BspMap.Brush brush : p.brushes()) {
				AABB box = brush.mcBox();
				// Its top no more than the slab's height over his soles, and most of him over it.
				if (box.intersects(feet) && box.maxY > feet.minY && box.maxY <= feet.maxY && (Double.isNaN(top) || box.maxY > top)) {
					top = box.maxY;
				}
			}
		}
		return top;
	}

	/** True if the feet of `body` are on or in one of the host's fixtures (a lift's platform, a button, a door). */
	public static boolean onFixture(AABB body) {
		return overlaps(new AABB(body.minX, body.minY - 0.1, body.minZ, body.maxX, body.minY + 0.5, body.maxZ), false);
	}

	private static boolean overlaps(AABB body, boolean movable) {
		for (Placed p : PLACED.values()) {
			if (p.bounds() == null || movableProp(p.pose().model()) != movable || !p.bounds().intersects(body)) {
				continue;
			}
			for (BspMap.Brush brush : p.brushes()) {
				if (brush.mcBox().intersects(body)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * A movable prop is this much bigger to Steve than it is (units). His hull stops against
	 * Minecraft's copy of it, which is a tick old and built of columns that can fall a unit short;
	 * without the skin Portal's own player, played back where Steve stands, ended up a hair inside
	 * the real cube, and Portal's physics threw the two apart: Steve hopped and shook pushing a
	 * cube or holding one against a wall. Half a unit, so he doesn't stand visibly off it; the
	 * plugin's own sweep against Portal's props (clampToProps) catches what this doesn't, and Steve
	 * pushes a cube he walks into himself (HostDriver.pushProps).
	 */
	private static final double PROP_SKIN = 0.5;

	/**
	 * The models the host has shown loose (HostEntity flag kEntityLoose: a physics prop, a turret, a
	 * GLaDOS core, by class and with no parent). A model seen loose once is loose: this was a list
	 * of names (metal_box, turret, radio), and a core or an office chair, missing from it, was a
	 * wall to Steve even in his own hands.
	 */
	private static final java.util.Set<String> LOOSE_MODELS = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/** A movable prop by model: one the host has flagged loose, or Portal 1's cubes, turrets and radio by name. */
	private static boolean movableProp(String model) {
		return model.endsWith(".mdl") && (LOOSE_MODELS.contains(model) || model.contains("metal_box") || model.contains("turret") || model.contains("radio"));
	}
	private static final Map<String, StaticProps.@Nullable Shape> SHAPES = new HashMap<>();
	private static @Nullable BspMap shapesFor;
	private static @Nullable GameFiles files;
	private static int lastSeq = -1;
	private static boolean loggedFirst;
	private static int movedLogs;
	private static int seenLogs;

	private LiveEntities() {
	}

	/** An entity that moved in the latest update: where it was, by how much (blocks), and whether it is a loose prop (a cube). */
	public record Moved(AABB before, Vec3 delta, boolean loose) {
	}

	/** Applies the newest entity packet. Returns the entities that moved, for carrying riders. */
	public static List<Moved> update(Proto.@Nullable HostEntities packet) {
		BspMap map = HostCollision.map();
		if (packet == null || map == null || packet.seq() == lastSeq) {
			return List.of();
		}
		lastSeq = packet.seq();
		if (shapesFor != map) {
			shapesFor = map;
			PLACED.clear();
			props = List.of();
			SHAPES.clear();
			try {
				seenLogs = 0; // a new map: its entities are logged afresh
				movedLogs = 0;
				files = GameFiles.forMap(map.file);
			} catch (Exception e) {
				files = null;
				LOG.warn("PortalCraft: no game files for {} ({}); physics props get boxes", map.name, e.toString());
			}
		}

		List<Moved> moved = new ArrayList<>();
		List<AABB> dirty = new ArrayList<>();
		Map<Integer, Placed> next = new HashMap<>();
		boolean changed = false;
		for (Proto.HostEntity e : packet.entities()) {
			Placed old = PLACED.get(e.index());
			if ((e.flags() & Proto.ENTITY_LOOSE) != 0 && e.model().endsWith(".mdl") && LOOSE_MODELS.add(e.model())) {
				LOG.info("PortalCraft: {} is a loose prop (the host says so)", e.model());
			}
			Vec3 holder = carrying;
			boolean carried = holder != null && movableProp(e.model()) && e.origin().distanceTo(holder.add(0.0, 0.0, 36.0)) < CARRY_REACH;
			if (old != null && old.carried() == carried && samePlace(old.pose(), e)) {
				next.put(e.index(), old);
				continue;
			}
			changed = true;
			List<BspMap.Brush> brushes = carried ? List.of() : place(map, e);
			AABB bounds = union(brushes);
			Placed placed = new Placed(e, brushes, bounds, carried);
			next.put(e.index(), placed);
			if (bounds != null) {
				dirty.add(bounds);
			}
			if (old != null && old.bounds() != null) {
				dirty.add(old.bounds());
			}
			if (old == null && seenLogs < 200) {
				seenLogs++;
				LOG.info("PortalCraft: entity #{} {} at {} ({} brushes, bounds {})", e.index(), e.model(), e.origin(), brushes.size(),
					bounds == null ? "none" : Units.toSrc(new Vec3(bounds.minX, bounds.minY, bounds.minZ)) + " to " + Units.toSrc(new Vec3(bounds.maxX, bounds.maxY, bounds.maxZ)));
			}
			if (old != null && movedLogs < 40) {
				movedLogs++;
				LOG.info("PortalCraft: entity #{} {} moved to {}", e.index(), e.model(), e.origin());
			}
			if (old != null && old.bounds() != null && old.pose().model().equals(e.model())) {
				moved.add(new Moved(old.bounds(), Units.toMc(e.origin()).subtract(Units.toMc(old.pose().origin())), movableProp(e.model())));
			}
		}
		for (Map.Entry<Integer, Placed> gone : PLACED.entrySet()) {
			if (!next.containsKey(gone.getKey())) {
				changed = true;
				if (gone.getValue().bounds() != null) {
					dirty.add(gone.getValue().bounds());
				}
			}
		}
		if (!changed) {
			return moved;
		}
		PLACED.clear();
		PLACED.putAll(next);
		List<Prop> nextProps = new ArrayList<>();
		for (Placed p : PLACED.values()) {
			if (p.bounds() != null && movableProp(p.pose().model())) {
				nextProps.add(new Prop(p.bounds(), p.pose().model().contains("turret")));
			}
		}
		props = List.copyOf(nextProps);
		List<BspMap.Brush> all = new ArrayList<>();
		for (Placed p : PLACED.values()) {
			all.addAll(p.brushes());
		}
		HostCollision.setDynamic(all, dirty);
		if (!loggedFirst) {
			loggedFirst = true;
			LOG.info("PortalCraft: {} live solid entities, {} brushes", PLACED.size(), all.size());
		}
		return moved;
	}

	/**
	 * The host entity whose collision holds `point` (Minecraft coordinates, within `margin`
	 * blocks), or -1. The smallest one wins, so a cube on a lift is the cube.
	 */
	/** Where host entity `index` is (host units), or null if it isn't one of the solid ones we hold. */
	public static @Nullable Vec3 originOf(int index) {
		Placed p = PLACED.get(index);
		return p == null ? null : p.pose().origin();
	}

	/** True if host entity `index` is a movable prop (a cube, a turret, the radio). */
	public static boolean movable(int index) {
		Placed p = PLACED.get(index);
		return p != null && movableProp(p.pose().model());
	}

	public static int entityAt(Vec3 point, double margin) {
		int best = -1;
		double bestSize = Double.MAX_VALUE;
		for (Placed p : PLACED.values()) {
			if (p.bounds() == null || !p.bounds().inflate(margin).contains(point)) {
				continue;
			}
			for (BspMap.Brush brush : p.brushes()) {
				if (brush.mcBox().inflate(margin).contains(point)) {
					double size = p.bounds().getSize();
					if (size < bestSize) {
						bestSize = size;
						best = p.pose().index();
					}
					break;
				}
			}
		}
		return best;
	}

	private static boolean samePlace(Proto.HostEntity a, Proto.HostEntity b) {
		return a.solid() == b.solid() && a.model().equals(b.model()) && a.origin().distanceToSqr(b.origin()) < 0.01
			&& a.angles().distanceToSqr(b.angles()) < 0.01 && a.mins().equals(b.mins()) && a.maxs().equals(b.maxs());
	}

	private static List<BspMap.Brush> place(BspMap map, Proto.HostEntity e) {
		List<BspMap.Brush> out = new ArrayList<>();
		// A security camera is nothing to Steve. It turns to watch the player, so its nine small
		// pieces sweep through wherever he is near it: beside the ceiling portal in testchmb_a_10 he
		// was lifted onto it, pushed back through the portal, or left standing on it in mid-air, by
		// turns. Portal's own player barely meets one (it hangs high on a wall).
		if (e.model().contains("security_camera")) {
			return out;
		}
		// Nor is an energy ball: it is a thing that kills on touch, which Portal sees to itself (its
		// player is where Steve is). As a solid box in Minecraft it would carry or block him instead.
		if (e.model().contains("combineball")) {
			return out;
		}
		// Nor is Portal's own player model. Near a portal Portal keeps a physics clone of its player on
		// the other side (its shadow clones, for things that straddle a portal): a solid copy of Chell
		// standing just outside the exit Steve is about to come out of. As a solid in Minecraft it
		// stopped him dead in the portal's mouth about two times in five, and Portal then took its
		// player back through (seen: three crossings for one, a hard hand-over).
		if (e.model().contains("models/player/")) {
			return out;
		}
		Vec3[] axes = Units.angleVectors(e.angles());
		String model = e.model();
		if (e.solid() == SOLID_BSP && model.startsWith("*")) {
			int n;
			try {
				n = Integer.parseInt(model.substring(1));
			} catch (NumberFormatException ex) {
				return out;
			}
			for (BspMap.Brush local : map.models.getOrDefault(n, List.of())) {
				BspMap.Brush b = local.transformed(e.origin(), axes);
				if (b != null) {
					out.add(b);
				}
			}
		} else if (e.solid() == SOLID_VPHYSICS && model.endsWith(".mdl") && files != null) {
			StaticProps.Shape shape = SHAPES.computeIfAbsent(model, m -> StaticProps.shape(files, m, true));
			if (shape != null) {
				for (Phy.Hull hull : shape.hulls()) {
					BspMap.Brush b = StaticProps.brush(hull, e.origin(), axes, shape.fromPhy() ? StaticProps.COLLISION_MARGIN : 0.0);
					if (b != null) {
						out.add(b);
					}
				}
			}
		}
		if (!out.isEmpty() && movableProp(model)) {
			// A movable prop is an upright box to Steve: its own box, turned the way it is turned on
			// the floor but never tipped. Tipped a few degrees, a cube's leaning face became a flight of
			// two-unit columns, each a step Steve could take, and pushing a cube into a corner he walked
			// up its side onto it. (The box around it squared to the world, as this was for a day,
			// stood up to 8 units proud of a cube turned 45 degrees.)
			Vec3 flat = axes[0];
			for (Vec3 axis : axes) {
				if (Math.abs(axis.z) < Math.abs(flat.z)) {
					flat = axis; // the most level of its three axes gives the turn
				}
			}
			Vec3[] turned = Units.angleVectors(new Vec3(0.0, Math.toDegrees(Math.atan2(flat.y, flat.x)), 0.0));
			Vec3 lo = new Vec3(1e9, 1e9, 1e9), hi = new Vec3(-1e9, -1e9, -1e9);
			Vec3 mins = e.mins(), maxs = e.maxs();
			for (int corner = 0; corner < 8; corner++) {
				Vec3 local = new Vec3((corner & 1) == 0 ? mins.x : maxs.x, (corner & 2) == 0 ? mins.y : maxs.y, (corner & 4) == 0 ? mins.z : maxs.z);
				Vec3 world = axes[0].scale(local.x).subtract(axes[1].scale(local.y)).add(axes[2].scale(local.z)); // Source's y is to the left
				Vec3 in = new Vec3(world.dot(turned[0]), -world.dot(turned[1]), world.z);
				lo = new Vec3(Math.min(lo.x, in.x), Math.min(lo.y, in.y), Math.min(lo.z, in.z));
				hi = new Vec3(Math.max(hi.x, in.x), Math.max(hi.y, in.y), Math.max(hi.z, in.z));
			}
			// The skin on its sides only. Its top is where Portal has it: half a unit higher, Portal's
			// player (set where Steve stands) dropped that half unit onto the real cube every tick, and
			// Portal moved it back along the cube as it did: Steve couldn't walk off a cube he stood on.
			BspMap.Brush upright = box(lo.subtract(PROP_SKIN, PROP_SKIN, PROP_SKIN), hi.add(PROP_SKIN, PROP_SKIN, 0.03)).transformed(e.origin(), turned);
			if (upright != null) {
				out.clear();
				out.add(upright);
			}
		}
		if (out.isEmpty() && (e.solid() == SOLID_BBOX || e.solid() == SOLID_OBB || e.solid() == SOLID_OBB_YAW || e.solid() == SOLID_VPHYSICS)) {
			// A box from the entity's own bounds: world-aligned for BBOX, rotated (or yaw only) for OBB.
			Vec3[] boxAxes = e.solid() == SOLID_BBOX ? WORLD_AXES
				: e.solid() == SOLID_OBB_YAW ? Units.angleVectors(new Vec3(0, e.angles().y, 0)) : axes;
			BspMap.Brush b = box(e.mins(), e.maxs()).transformed(e.origin(), boxAxes);
			if (b != null) {
				out.add(b);
			}
		}
		return out;
	}

	/** An axis-aligned box in an entity's own space, as six planes. */
	private static BspMap.Brush box(Vec3 lo, Vec3 hi) {
		float[] planes = {
			1, 0, 0, (float) hi.x, -1, 0, 0, (float) -lo.x,
			0, 1, 0, (float) hi.y, 0, -1, 0, (float) -lo.y,
			0, 0, 1, (float) hi.z, 0, 0, -1, (float) -lo.z,
		};
		return new BspMap.Brush(new AABB(Units.toMc(lo), Units.toMc(hi)), planes, false);
	}

	private static @Nullable AABB union(List<BspMap.Brush> brushes) {
		AABB out = null;
		for (BspMap.Brush b : brushes) {
			out = out == null ? b.mcBox() : out.minmax(b.mcBox());
		}
		return out;
	}
}
