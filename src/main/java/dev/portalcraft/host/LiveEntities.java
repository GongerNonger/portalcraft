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
	public record Placed(Proto.HostEntity pose, List<BspMap.Brush> brushes, @Nullable AABB bounds) {
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

	/** Portal 1's movable props by model: cubes (metal_box), turrets, the radio. Floor buttons and the rest stay put. */
	private static boolean movableProp(String model) {
		return model.endsWith(".mdl") && (model.contains("metal_box") || model.contains("turret") || model.contains("radio"));
	}
	private static final Map<String, StaticProps.@Nullable Shape> SHAPES = new HashMap<>();
	private static @Nullable BspMap shapesFor;
	private static @Nullable GameFiles files;
	private static int lastSeq = -1;
	private static boolean loggedFirst;
	private static int movedLogs;

	private LiveEntities() {
	}

	/** An entity that moved in the latest update: where it was, and by how much (blocks). */
	public record Moved(AABB before, Vec3 delta) {
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
			if (old != null && samePlace(old.pose(), e)) {
				next.put(e.index(), old);
				continue;
			}
			changed = true;
			List<BspMap.Brush> brushes = place(map, e);
			AABB bounds = union(brushes);
			Placed placed = new Placed(e, brushes, bounds);
			next.put(e.index(), placed);
			if (bounds != null) {
				dirty.add(bounds);
			}
			if (old != null && old.bounds() != null) {
				dirty.add(old.bounds());
			}
			if (old != null && movedLogs < 40) {
				movedLogs++;
				LOG.info("PortalCraft: entity #{} {} moved to {}", e.index(), e.model(), e.origin());
			}
			if (old != null && old.bounds() != null && old.pose().model().equals(e.model())) {
				moved.add(new Moved(old.bounds(), Units.toMc(e.origin()).subtract(Units.toMc(old.pose().origin()))));
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
