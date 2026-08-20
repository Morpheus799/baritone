/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.behavior;

import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Persistent trigger for {@link baritone.api.Settings#backfillOnMobLock}.
 * <p>
 * While a hostile mob within {@code mobLockRange} has line of sight to the player and is facing them,
 * this temporarily enables backfill (via the managed {@code backfillTmp} flag) so the tunnel behind the
 * player is sealed, breaking the mob's line of sight so it loses aggro. It clears automatically once no
 * mob is locked on. The user's {@code backfill} setting is never touched.
 *
 * @see baritone.process.BackfillProcess
 */
public final class MobBackfillBehavior extends Behavior {

    /**
     * Keep backfill enabled for this many ticks after the last detected lock, so it doesn't flap on and
     * off as mobs briefly turn away or line of sight is momentarily interrupted.
     */
    private static final int KEEP_TICKS = 40;

    /**
     * Facing tolerance: the mob's view vector dotted with the (normalized) direction to the player must
     * exceed this for the mob to count as "looking at" the player (~within 60 degrees).
     */
    private static final double FACING_DOT = 0.5D;

    private int lockCooldown;

    public MobBackfillBehavior(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() != TickEvent.Type.IN) {
            return;
        }
        if (!Baritone.settings().backfillOnMobLock.value) {
            if (Baritone.settings().backfillTmp.value) {
                Baritone.settings().backfillTmp.value = false;
            }
            lockCooldown = 0;
            return;
        }
        if (ctx.player() == null || ctx.world() == null) {
            return;
        }
        if (anyMobLockedOn()) {
            lockCooldown = KEEP_TICKS;
        } else if (lockCooldown > 0) {
            lockCooldown--;
        }
        Baritone.settings().backfillTmp.value = lockCooldown > 0;
    }

    private boolean anyMobLockedOn() {
        double range = Baritone.settings().mobLockRange.value;
        double rangeSq = range * range;
        Vec3 playerEye = ctx.player().getEyePosition();
        return ctx.entitiesStream()
                .filter(entity -> entity instanceof Enemy)
                .anyMatch(entity -> isLockedOn(entity, playerEye, rangeSq));
    }

    private boolean isLockedOn(Entity mob, Vec3 playerEye, double rangeSq) {
        Vec3 mobEye = mob.getEyePosition();
        if (mobEye.distanceToSqr(playerEye) > rangeSq) {
            return false;
        }
        // the mob must be roughly facing the player
        Vec3 toPlayer = playerEye.subtract(mobEye).normalize();
        if (mob.getViewVector(1.0F).dot(toPlayer) < FACING_DOT) {
            return false;
        }
        // ...and actually see them (no collider between the two eye positions)
        HitResult hit = ctx.world().clip(new ClipContext(mobEye, playerEye, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mob));
        return hit.getType() == HitResult.Type.MISS;
    }
}
