package dev.portalcraft.host;

import java.util.List;

import dev.portalcraft.PortalCraft;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;

/**
 * Steve at Portal's size, in a world where a block is 32 of Portal's units.
 *
 * Portal builds on a 64-unit grid: with 32 units to the block its floors, walls and steps land on
 * whole blocks, and a block Steve places sits flush on a chamber's floor. (At 40, the number that
 * made an unscaled Steve as tall as Portal's player, a floor 64 units up was 1.6 blocks up and
 * blocks sat half sunk into most floors.) A 1.8-block Steve would then be 58 units tall in doorways
 * and under ledges made for 72, so inside a host map he is scaled up by 1.25, and everything that is
 * counted in blocks with him: his walking speed, his jump (the same 50 units high, in the same
 * time), the step he climbs, his reach, and how far he falls unhurt. His movement in Portal's
 * units is what it was.
 */
public final class HostScale {
	/** How much bigger than a vanilla player: 40 units to the block made 1.0 the right size. */
	public static final double STEVE = 40.0 / Units.PER_BLOCK;

	private static final Identifier ID = PortalCraft.id("host_scale");
	/** Lengths and speeds in blocks: all up by the scale. */
	private static final List<Holder<Attribute>> LENGTHS = List.of(Attributes.SCALE, Attributes.MOVEMENT_SPEED, Attributes.JUMP_STRENGTH, Attributes.GRAVITY,
		Attributes.STEP_HEIGHT, Attributes.SAFE_FALL_DISTANCE, Attributes.BLOCK_INTERACTION_RANGE, Attributes.ENTITY_INTERACTION_RANGE);

	private HostScale() {
	}

	/** END_LEVEL_TICK, server thread: the players in the host's world wear the scale; anywhere else it comes off. */
	public static void tick(ServerLevel level) {
		boolean hosted = HostCollision.active() && level.dimension() == Level.OVERWORLD && STEVE != 1.0;
		for (ServerPlayer player : level.players()) {
			for (Holder<Attribute> attribute : LENGTHS) {
				set(player, attribute, hosted ? STEVE - 1.0 : 0.0);
			}
			// Damage per block fallen: down by the scale, so a fall hurts by its height in units as before.
			set(player, Attributes.FALL_DAMAGE_MULTIPLIER, hosted ? 1.0 / STEVE - 1.0 : 0.0);
		}
	}

	private static void set(ServerPlayer player, Holder<Attribute> attribute, double extra) {
		AttributeInstance instance = player.getAttribute(attribute);
		if (instance == null) {
			return;
		}
		AttributeModifier now = instance.getModifier(ID);
		if (extra == 0.0) {
			if (now != null) {
				instance.removeModifier(ID);
			}
		} else if (now == null || now.amount() != extra) {
			instance.addOrUpdateTransientModifier(new AttributeModifier(ID, extra, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		}
	}
}
