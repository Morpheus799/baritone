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
import baritone.api.utils.Helper;
import baritone.utils.ToolSet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Persistent trigger for {@link baritone.api.Settings#backfillOnMobLock}.
 * <p>
 * A hostile mob counts as a threat when it is either <em>close</em> (within {@code mobProximityRange},
 * regardless of facing/line of sight) or <em>locked on</em> (line of sight to the player with its head
 * turned toward the player, from up to {@code mobLockRange}). While threatened, this temporarily enables
 * backfill (via the managed {@code backfillTmp} flag) so the tunnel behind the player is sealed, breaking
 * line of sight so the mob loses aggro. It clears automatically once no threat remains. The user's
 * {@code backfill} setting is never touched.
 * <p>
 * The moment a threat first appears, it also forces an immediate re-path (abandoning the current path
 * segment) so the reaction doesn't wait for the next segment boundary; for {@code #mine} this re-selects
 * the target block too, since {@link baritone.process.MineProcess} recomputes its goal every tick.
 * <p>
 * When enabled, this writes debug lines to {@code logs/baritone.log} (and to chat when
 * {@code chatDebug} is on): the on/off transitions, and &mdash; while nothing is locked &mdash; why
 * the nearest hostile mob doesn't qualify (deduplicated by reason).
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
     * Facing tolerance: the cosine between the mob's head-forward direction and the (horizontal)
     * direction to the player must exceed this for the mob to count as "staring at" the player
     * (~within 60 degrees).
     */
    private static final double FACING_DOT = 0.5D;

    private int lockCooldown;
    /** Last logged "not locked" reason category, so the diagnostic doesn't spam every tick. */
    private String lastDiag;

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
            lastDiag = null;
            return;
        }
        if (ctx.player() == null || ctx.world() == null) {
            return;
        }

        double range = Baritone.settings().mobLockRange.value;
        double rangeSq = range * range;
        double proximity = Baritone.settings().mobProximityRange.value;
        double proximitySq = proximity * proximity;
        Vec3 playerEye = ctx.player().getEyePosition();

        Entity locked = null;
        Entity nearest = null;
        double nearestSq = Double.MAX_VALUE;
        for (Entity e : ctx.entities()) {
            if (!(e instanceof Enemy)) {
                continue;
            }
            double dsq = e.getEyePosition().distanceToSqr(playerEye);
            if (dsq < nearestSq) {
                nearestSq = dsq;
                nearest = e;
            }
            if (locked == null && isLockedOn(e, playerEye, rangeSq)) {
                locked = e;
            }
        }

        // a hostile is a threat if it locked on (line of sight + facing) or is simply very close
        boolean near = nearest != null && nearestSq <= proximitySq;
        boolean threat = locked != null || near;
        if (threat) {
            lockCooldown = KEEP_TICKS;
        } else if (lockCooldown > 0) {
            lockCooldown--;
        }

        boolean want = lockCooldown > 0;
        boolean was = Baritone.settings().backfillTmp.value;
        if (want != was) {
            Baritone.settings().backfillTmp.value = want;
            if (want) {
                Entity m = locked != null ? locked : nearest;
                String why = locked != null ? "locked by " : "hostile within " + fmt(proximity) + "m: ";
                log("ON — " + why + (m == null ? "?" : describe(m, playerEye)));
                // a new threat just appeared: reassess now instead of waiting for the next segment
                // boundary — recompute the target and re-path so backfill seals the chosen route
                forceReplan();
            } else {
                log("OFF — no threat for " + KEEP_TICKS + " ticks");
            }
            lastDiag = null; // let the diagnostic re-report after a state change
        } else if (!want) {
            // enabled but no threat: record why the nearest hostile doesn't qualify (deduped)
            diagnoseNotLocked(nearest, playerEye, range);
        }
    }

    /**
     * Abandon the current path segment so the in-control process re-plans immediately. {@code #mine}
     * re-selects its target block as part of that, since {@link baritone.process.MineProcess} recomputes
     * its goal every tick. No-op when nothing is being pathed.
     */
    private void forceReplan() {
        if (baritone.getPathingBehavior().isPathing()) {
            baritone.getPathingBehavior().secretInternalSegmentCancel();
            log("forced re-path");
        }
    }

    private boolean isLockedOn(Entity mob, Vec3 playerEye, double rangeSq) {
        Vec3 mobEye = mob.getEyePosition();
        return mobEye.distanceToSqr(playerEye) <= rangeSq
                && headFacingDot(mob, playerEye) >= FACING_DOT
                && hasLineOfSight(mob, mobEye, playerEye);
    }

    /**
     * Cosine between where the mob's head is pointing (horizontally) and the horizontal direction to
     * the player. Uses head yaw ({@link Entity#getYHeadRot()}) rather than the body view vector,
     * because a mob staring at the player turns its head, not necessarily its body.
     */
    private double headFacingDot(Entity mob, Vec3 playerEye) {
        Vec3 mobEye = mob.getEyePosition();
        double yaw = Math.toRadians(mob.getYHeadRot());
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        double dx = playerEye.x - mobEye.x;
        double dz = playerEye.z - mobEye.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0e-6) {
            return 1.0;
        }
        return (fx * dx + fz * dz) / len;
    }

    private boolean hasLineOfSight(Entity mob, Vec3 mobEye, Vec3 playerEye) {
        return ctx.world().clip(new ClipContext(mobEye, playerEye, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mob))
                .getType() == HitResult.Type.MISS;
    }

    private void diagnoseNotLocked(Entity nearest, Vec3 playerEye, double range) {
        String category;
        String detail;
        if (nearest == null) {
            category = "none";
            detail = "no hostile mobs loaded";
        } else {
            Vec3 mobEye = nearest.getEyePosition();
            double dist = Math.sqrt(mobEye.distanceToSqr(playerEye));
            String who = nearest.getName().getString() + " @ " + fmt(dist) + "m";
            if (dist > range) {
                category = "range";
                detail = who + " out of range (>" + fmt(range) + ")";
            } else {
                double dot = headFacingDot(nearest, playerEye);
                if (dot < FACING_DOT) {
                    category = "facing";
                    detail = who + " not facing (headDot=" + fmt(dot) + " < " + FACING_DOT + ")";
                } else if (!hasLineOfSight(nearest, mobEye, playerEye)) {
                    category = "los";
                    detail = who + " no line of sight";
                } else {
                    category = "pass";
                    detail = who + " passes all checks";
                }
            }
        }
        if (!category.equals(lastDiag)) {
            lastDiag = category;
            log("not locked — " + detail);
        }
    }

    private String describe(Entity mob, Vec3 playerEye) {
        Vec3 mobEye = mob.getEyePosition();
        double dist = Math.sqrt(mobEye.distanceToSqr(playerEye));
        return mob.getName().getString() + " @ " + fmt(dist) + "m (headDot=" + fmt(headFacingDot(mob, playerEye))
                + ", los=" + (hasLineOfSight(mob, mobEye, playerEye) ? "yes" : "no") + ")";
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    private void log(String line) {
        ToolSet.appendToLogFile("[MobBackfill] " + line);
        if (Baritone.settings().chatDebug.value) {
            Helper.HELPER.logDebug("[MobBackfill] " + line);
        }
    }
}
