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

package baritone.utils;

import baritone.api.utils.IPlayerContext;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.LlamaSpit;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.arrow.SpectralArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.AbstractWindCharge;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEgg;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownExperienceBottle;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownLingeringPotion;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Steps an already-fired projectile forward tick-by-tick to predict its trajectory, client-side.
 * <p>
 * A trimmed port of Meteor Client's {@code ProjectileEntitySimulator} (fired-projectile path only):
 * no held-item prediction, no entity collision, no water handling. The per-type gravity/drag constants
 * and the per-class physics ordering match vanilla so the prediction lines up with the real projectile.
 *
 * @see baritone.process.DodgeProcess
 */
public final class ProjectileTrajectory {

    // gravity applied downward and the multiplicative air drag applied each tick, per projectile family.
    // (see https://minecraft.wiki/w/Entity#Motion). Water drag is ignored; we assume flight through air.
    private static final double G_ARROW = 0.05, D_ARROW = 0.99;
    private static final double G_THROWN = 0.03, D_THROWN = 0.99;   // egg / snowball / ender pearl
    private static final double G_XP = 0.07, D_XP = 0.99;           // experience bottle
    private static final double G_POTION = 0.05, D_POTION = 0.99;   // splash / lingering potion
    private static final double G_LLAMA = 0.06, D_LLAMA = 0.99;
    private static final double G_EXPLOSIVE = 0.0, D_EXPLOSIVE = 1.0; // fireball / wither skull / wind charge

    // physics ordering families
    private static final int ORDER_POS_LAST = 0;  // gravity -> drag -> position (throwable / hurting)
    private static final int ORDER_POS_FIRST = 1; // position -> drag -> gravity (arrow / llama spit)

    private final IPlayerContext ctx;

    private Entity simEntity;
    private double px, py, pz;
    private double vx, vy, vz;
    private double gravity, drag;
    private int order;

    public ProjectileTrajectory(IPlayerContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Initialise the simulation from a live projectile. Returns {@code false} if the entity is not a
     * projectile whose motion we model, or if it is an arrow that has already stuck in a block.
     */
    public boolean set(Entity e) {
        if (!(e instanceof Projectile)) {
            return false;
        }
        // a stuck arrow has essentially zero velocity; skip it (cheaper than an isInGround accessor mixin)
        if (e instanceof AbstractArrow && e.getDeltaMovement().lengthSqr() < 1.0e-6) {
            return false;
        }

        switch (e) {
            case Arrow ignored -> setData(G_ARROW, D_ARROW, ORDER_POS_FIRST);
            case SpectralArrow ignored -> setData(G_ARROW, D_ARROW, ORDER_POS_FIRST);
            case ThrownTrident ignored -> setData(G_ARROW, D_ARROW, ORDER_POS_FIRST);
            case LlamaSpit ignored -> setData(G_LLAMA, D_LLAMA, ORDER_POS_FIRST);
            case ThrownEnderpearl ignored -> setData(G_THROWN, D_THROWN, ORDER_POS_LAST);
            case Snowball ignored -> setData(G_THROWN, D_THROWN, ORDER_POS_LAST);
            case ThrownEgg ignored -> setData(G_THROWN, D_THROWN, ORDER_POS_LAST);
            case ThrownExperienceBottle ignored -> setData(G_XP, D_XP, ORDER_POS_LAST);
            case ThrownSplashPotion ignored -> setData(G_POTION, D_POTION, ORDER_POS_LAST);
            case ThrownLingeringPotion ignored -> setData(G_POTION, D_POTION, ORDER_POS_LAST);
            case AbstractWindCharge ignored -> setData(G_EXPLOSIVE, D_EXPLOSIVE, ORDER_POS_LAST);
            case AbstractHurtingProjectile ignored -> setData(G_EXPLOSIVE, D_EXPLOSIVE, ORDER_POS_LAST);
            default -> {
                return false;
            }
        }

        if (e.isNoGravity()) {
            this.gravity = 0;
        }
        this.simEntity = e;
        Vec3 p = e.position();
        this.px = p.x;
        this.py = p.y;
        this.pz = p.z;
        Vec3 v = e.getDeltaMovement();
        this.vx = v.x;
        this.vy = v.y;
        this.vz = v.z;
        return true;
    }

    private void setData(double gravity, double drag, int order) {
        this.gravity = gravity;
        this.drag = drag;
        this.order = order;
    }

    /**
     * @return the projectile's current simulated position (a fresh immutable vector each call).
     */
    public Vec3 pos() {
        return new Vec3(px, py, pz);
    }

    /**
     * Advance the simulation by one tick.
     *
     * @return {@code true} if the projectile has hit a block, left the world, or entered an unloaded
     *         chunk &mdash; i.e. the caller should stop stepping.
     */
    public boolean step() {
        double prevX = px, prevY = py, prevZ = pz;

        if (order == ORDER_POS_FIRST) { // position -> drag -> gravity (arrow / llama spit)
            px += vx;
            py += vy;
            pz += vz;
            vx *= drag;
            vy *= drag;
            vz *= drag;
            vy -= gravity;
        } else { // gravity -> drag -> position (throwable / hurting)
            vy -= gravity;
            vx *= drag;
            vy *= drag;
            vz *= drag;
            px += vx;
            py += vy;
            pz += vz;
        }

        if (py < ctx.world().getMinY()) {
            return true;
        }
        if (!ctx.world().getChunkSource().hasChunk(SectionPos.posToSectionCoord(px), SectionPos.posToSectionCoord(pz))) {
            return true;
        }

        Vec3 prev = new Vec3(prevX, prevY, prevZ);
        Vec3 cur = new Vec3(px, py, pz);
        if (prev.equals(cur)) {
            return true;
        }
        HitResult hit = ctx.world().clip(new ClipContext(prev, cur, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, simEntity));
        if (hit.getType() != HitResult.Type.MISS) {
            Vec3 loc = hit.getLocation();
            px = loc.x;
            py = loc.y;
            pz = loc.z;
            return true;
        }
        return false;
    }
}
