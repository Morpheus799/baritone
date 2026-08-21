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

package baritone.process;

import baritone.Baritone;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.input.Input;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.ProjectileTrajectory;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.arrow.SpectralArrow;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Predicts incoming projectile trajectories and, while Baritone is executing a task, strafes the player
 * out of the way using normal movement keys ({@link Input}) before resuming the task. This is Baritone's
 * built-in equivalent of an external "arrow dodge" mod, needed because Baritone's movement takeover
 * (see {@code InputOverrideHandler}) otherwise blocks such mods during a task.
 * <p>
 * A temporary, high-priority process: while a dodge is needed it wins arbitration and returns
 * {@link PathingCommandType#REQUEST_PAUSE}, so the underlying process (e.g. mining) keeps its path and
 * resumes untouched once the projectile has passed.
 * <p>
 * Detection and the safe-direction search are adapted from Meteor Client's {@code ArrowDodge}; movement
 * is done with keys (not velocity/packets) to stay anti-cheat friendly.
 */
public final class DodgeProcess extends BaritoneProcessHelper {

    /** The eight movement combos as {@code {leftImpulse, forwardImpulse}} (matching PlayerMovementInput). */
    private static final int[][] MOVE_COMBOS = {
            {0, 1}, {0, -1}, {1, 0}, {-1, 0}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}
    };

    /** How far ahead (blocks) to test a candidate strafe when checking whether it clears the trajectory. */
    private static final double LOOKAHEAD = 0.8;

    private final ProjectileTrajectory trajectory;
    private final List<Vec3> points = new ArrayList<>();
    private final List<int[]> combos = new ArrayList<>(List.of(MOVE_COMBOS));

    private int planTick = -1;
    private boolean dodgeNeeded;
    private int chosenLeft, chosenForward;

    public DodgeProcess(Baritone baritone) {
        super(baritone);
        this.trajectory = new ProjectileTrajectory(ctx);
    }

    @Override
    public boolean isActive() {
        if (ctx.player() == null || ctx.world() == null) {
            return false;
        }
        if (!Baritone.settings().dodgeProjectiles.value) {
            return false;
        }
        // only while a task is running (a goal is set or a path is executing). getGoal()/getCurrent()
        // stay non-null through our own REQUEST_PAUSE, so this doesn't flip-flop while we dodge.
        if (baritone.getPathingBehavior().getGoal() == null && baritone.getPathingBehavior().getCurrent() == null) {
            return false;
        }
        return plan();
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        plan(); // cached for this tick; ensures a decision even if isActive wasn't the caller
        baritone.getInputOverrideHandler().clearAllKeys();
        if (dodgeNeeded) {
            if (chosenForward > 0) {
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, true);
            } else if (chosenForward < 0) {
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, true);
            }
            if (chosenLeft > 0) {
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, true);
            } else if (chosenLeft < 0) {
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, true);
            }
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    /**
     * Runs at most once per tick (cached). Builds the trajectory point cloud for every threatening
     * projectile, decides whether standing still would be hit, and if so picks the best strafe combo.
     *
     * @return whether a dodge is needed this tick
     */
    private boolean plan() {
        int tick = ctx.player().tickCount;
        if (tick == planTick) {
            return dodgeNeeded;
        }
        planTick = tick;
        dodgeNeeded = false;

        points.clear();
        boolean all = Baritone.settings().dodgeAllProjectiles.value;
        boolean ignoreOwn = Baritone.settings().dodgeIgnoreOwn.value;
        int steps = Baritone.settings().dodgeSimulationSteps.value;
        if (steps <= 0) {
            steps = Integer.MAX_VALUE;
        }

        for (Entity e : ctx.entities()) {
            if (!(e instanceof Projectile projectile)) {
                continue;
            }
            if (!all && !(e instanceof Arrow || e instanceof SpectralArrow)) {
                continue;
            }
            if (ignoreOwn) {
                Entity owner = projectile.getOwner();
                if (owner != null && owner.getUUID().equals(ctx.player().getUUID())) {
                    continue;
                }
            }
            if (!trajectory.set(e)) {
                continue;
            }
            points.add(trajectory.pos());
            for (int i = 0; i < steps; i++) {
                if (trajectory.step()) {
                    break;
                }
                points.add(trajectory.pos());
            }
        }
        if (points.isEmpty()) {
            return false;
        }

        Vec3 feet = ctx.player().position();
        if (projectileSafe(feet)) {
            return false; // standing still is fine, no need to move
        }

        // pick a strafe: prefer a fully safe (clears the trajectory AND has safe footing) direction,
        // otherwise fall back to the ground-safe direction that keeps us furthest from any projectile.
        float yaw = ctx.player().getYRot();
        Collections.shuffle(combos); // unpredictable side, like Meteor
        int[] fallback = null;
        double bestClearance = -1;
        for (int[] combo : combos) {
            Vec3 dir = worldDir(yaw, combo[0], combo[1]);
            if (!groundSafe(dir)) {
                continue; // never dodge into a wall or off a ledge
            }
            Vec3 target = feet.add(dir.scale(LOOKAHEAD));
            if (projectileSafe(target)) {
                chosenLeft = combo[0];
                chosenForward = combo[1];
                dodgeNeeded = true;
                return true;
            }
            double clearance = minClearanceSq(target);
            if (clearance > bestClearance) {
                bestClearance = clearance;
                fallback = combo;
            }
        }
        if (fallback == null) {
            return false; // boxed in with no safe footing anywhere; don't thrash
        }
        chosenLeft = fallback[0];
        chosenForward = fallback[1];
        dodgeNeeded = true;
        return true;
    }

    /** Whether no predicted projectile position comes within {@code dodgeDistanceCheck} of feet/head. */
    private boolean projectileSafe(Vec3 playerPos) {
        Vec3 head = playerPos.add(0, 1, 0);
        double d = Baritone.settings().dodgeDistanceCheck.value;
        for (Vec3 pt : points) {
            if (pt.closerThan(playerPos, d) || pt.closerThan(head, d)) {
                return false;
            }
        }
        return true;
    }

    private double minClearanceSq(Vec3 playerPos) {
        Vec3 head = playerPos.add(0, 1, 0);
        double min = Double.MAX_VALUE;
        for (Vec3 pt : points) {
            min = Math.min(min, Math.min(pt.distanceToSqr(playerPos), pt.distanceToSqr(head)));
        }
        return min;
    }

    /** Whether the block one step in {@code dir} is enterable (feet+head clear) and, if enabled, has ground. */
    private boolean groundSafe(Vec3 dir) {
        // map the (normalised) world direction to the neighbouring block; the 0.35 threshold drops the
        // near-zero axis of a cardinal move while keeping both axes of a ~0.707 diagonal.
        int ox = Math.abs(dir.x) < 0.35 ? 0 : (dir.x > 0 ? 1 : -1);
        int oz = Math.abs(dir.z) < 0.35 ? 0 : (dir.z > 0 ? 1 : -1);
        BlockPos target = ctx.playerFeet().offset(ox, 0, oz);
        if (!collisionEmpty(target) || !collisionEmpty(target.above())) {
            return false;
        }
        if (Baritone.settings().dodgeGroundCheck.value) {
            return !collisionEmpty(target.below());
        }
        return true;
    }

    private boolean collisionEmpty(BlockPos pos) {
        return ctx.world().getBlockState(pos).getCollisionShape(ctx.world(), pos).isEmpty();
    }

    /**
     * Convert a {@code (leftImpulse, forwardImpulse)} movement combo into a horizontal world-space unit
     * direction for the player's current yaw, using the same math as vanilla movement input.
     */
    private static Vec3 worldDir(float yaw, int left, int forward) {
        double rad = Math.toRadians(yaw);
        double sin = Math.sin(rad);
        double cos = Math.cos(rad);
        double wx = left * cos - forward * sin;
        double wz = forward * cos + left * sin;
        return new Vec3(wx, 0, wz).normalize();
    }

    @Override
    public void onLostControl() {
        dodgeNeeded = false;
    }

    @Override
    public String displayName0() {
        return "Dodge projectiles";
    }

    @Override
    public double priority() {
        return 10; // above inventory pauser (5.1) and backfill (5): staying alive comes first
    }

    @Override
    public boolean isTemporary() {
        return true;
    }
}
