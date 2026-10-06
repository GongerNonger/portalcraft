package dev.portalcraft.mixin;

import java.util.List;

import dev.portalcraft.host.HostCollision;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Walking up the host's slopes at walking speed.
 *
 * A slope of the host's (a ramp, the wedges round a floor button) is a flight of columns two units
 * wide in Minecraft's collision. Minecraft's step-up takes the lowest step that gets a blocked
 * player any further at all, which on such a flight is the next column and no more: one column a
 * tick, 40 units a second, a third of a walk, with the rest of every step thrown away (Steve
 * crawled and shuddered up a button's rim that Portal's player walks straight over). Inside a host
 * map a blocked player on the ground instead takes the lowest rise (up to his step height) that
 * lets the whole move through, or failing that the one that gets him furthest, and is set down on
 * what is under him there.
 */
@Mixin(Entity.class)
public abstract class EntityStepMixin {
	/** Rises tried, in blocks: every sixteenth, a column's own width. */
	private static final double RISE = 1.0 / 16.0;

	@Inject(method = "collide", at = @At("RETURN"), cancellable = true)
	private void portalcraft$walkUpSlopes(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
		Entity self = (Entity) (Object) this;
		if (!(self instanceof Player) || !HostCollision.active()) {
			return;
		}
		Vec3 got = cir.getReturnValue();
		double wanted = movement.horizontalDistanceSqr();
		if (wanted < 1.0e-8 || got.horizontalDistanceSqr() >= wanted - 1.0e-9) {
			return; // not blocked
		}
		AABB box = self.getBoundingBox();
		// On the ground, or as good as: something under his feet within a quarter block. A floor
		// button's plate sinks under Steve as he crosses it, Minecraft has him "falling" all the way
		// down, and off the ground he couldn't step the few units back up onto its rim.
		if (!self.onGround() && movement.y <= 0.0
			&& Entity.collideBoundingBox(self, new Vec3(0.0, -0.25, 0.0), box, self.level(), List.of()).y <= -0.25 + 1.0e-6) {
			return;
		}
		if (!self.onGround() && movement.y > 0.0) {
			return; // on the way up in a jump
		}
		Vec3 best = null;
		double bestRise = 0.0, bestDistance = got.horizontalDistanceSqr() + 1.0e-9;
		for (double rise = RISE; rise <= self.maxUpStep() + 1.0e-6; rise += RISE) {
			Vec3 tried = Entity.collideBoundingBox(self, new Vec3(movement.x, rise, movement.z), box, self.level(), List.of());
			if (tried.y < rise - 1.0e-6) {
				break; // no headroom to rise that far
			}
			if (tried.horizontalDistanceSqr() > bestDistance) {
				best = tried;
				bestRise = rise;
				bestDistance = tried.horizontalDistanceSqr();
				if (bestDistance >= wanted - 1.0e-9) {
					break; // the whole move fits at this rise
				}
			}
		}
		if (best == null) {
			return;
		}
		Vec3 down = Entity.collideBoundingBox(self, new Vec3(0.0, -bestRise, 0.0), box.move(best), self.level(), List.of());
		cir.setReturnValue(new Vec3(best.x, best.y + down.y, best.z));
	}
}
