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
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.IRideProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.input.Input;
import baritone.api.utils.interfaces.IGoalRenderPos;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.BlockStateInterface;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Steers a mount or vehicle that the player is already riding towards a position.
 * <p>
 * This is not a pathfinder: it heads straight for the target and does a simple look-ahead
 * to steer around walls, drops, liquids (for land mounts) and non-water (for boats).
 * <p>
 * Land mounts (horse, donkey, mule, camel, pig) are steered with the player's yaw, which is what
 * the game uses to turn them. Boats are steered with the left/right/forward inputs.
 */
public final class RideProcess extends BaritoneProcessHelper implements IRideProcess {

    private enum Kind {
        HORSE, PIG, BOAT;

        boolean isBoat() {
            return this == BOAT;
        }
    }

    /**
     * Headings tried in order, as degrees away from the straight line to the target. The sign is
     * multiplied by {@link #lastSide} so we keep going around an obstacle on the same side.
     */
    private static final float[] DEVIATIONS = {0, 25, -25, 50, -50, 75, -75, 100, -100, 130, -130};

    /**
     * Max degrees the player's yaw (and so a land mount) turns per tick
     */
    private static final float MAX_LAND_TURN = 15.0F;

    /**
     * If we have moved less than {@link #STUCK_MIN_MOVE} blocks in this many ticks, give up
     */
    private static final int STUCK_TICKS = 80;
    private static final double STUCK_MIN_MOVE = 0.5;

    /**
     * Ticks to hold sneak after arriving, to dismount
     */
    private static final int DISMOUNT_TICKS = 3;

    private BlockPos target;
    private int dismountTicks;

    private Vec3 stuckReference;
    private int stuckTicks;
    private float lastSide = 1;

    /**
     * Ticks left to hold the jump key (a jump is charged by holding it and happens when it is released)
     */
    private int jumpHeld;
    private int jumpCooldown;

    public RideProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void rideTo(Goal goal) {
        if (goal instanceof GoalXZ) {
            rideTo(((GoalXZ) goal).getX(), ((GoalXZ) goal).getZ());
            return;
        }
        if (goal instanceof IGoalRenderPos) {
            BlockPos pos = ((IGoalRenderPos) goal).getGoalPos();
            rideTo(pos.getX(), pos.getZ());
            return;
        }
        throw new IllegalArgumentException("Can't ride to " + goal + ": only goals with a position (x z / x y z) are supported");
    }

    @Override
    public void rideTo(int x, int z) {
        Entity vehicle = requireSteerableVehicle();
        reset();
        this.target = new BlockPos(x, Mth.floor(vehicle.getY()), z);
        logDirect("Riding to " + x + ", " + z + " on " + vehicle.getName().getString() + ". #cancel or #ride stop to abort.");
    }

    @Override
    public BlockPos currentDestination() {
        return target;
    }

    @Override
    public void stop() {
        halt();
    }

    private Entity requireSteerableVehicle() {
        if (ctx.player() == null || ctx.world() == null) {
            throw new IllegalStateException("Not in a world");
        }
        Entity vehicle = ctx.player().getVehicle();
        if (vehicle == null) {
            throw new IllegalStateException("You must be riding something first (horse, donkey, mule, camel, pig or boat)");
        }
        if (kindOf(vehicle) == null) {
            throw new IllegalStateException(vehicle.getName().getString() + " can't be steered. Supported: horse, donkey, mule, camel, pig, boat");
        }
        if (vehicle.getControllingPassenger() != ctx.player()) {
            throw new IllegalStateException("You don't control " + vehicle.getName().getString() + " (is it tamed and saddled? holding a carrot on a stick for a pig?)");
        }
        return vehicle;
    }

    private static Kind kindOf(Entity vehicle) {
        if (vehicle instanceof AbstractHorse) {
            return Kind.HORSE;
        }
        if (vehicle instanceof Boat) {
            return Kind.BOAT;
        }
        if (vehicle instanceof Pig) {
            return Kind.PIG;
        }
        return null;
    }

    private void reset() {
        stuckReference = null;
        stuckTicks = 0;
        lastSide = 1;
        dismountTicks = 0;
        jumpHeld = 0;
        jumpCooldown = 0;
    }

    /**
     * Stop steering and release every key we may be holding
     */
    private void halt() {
        target = null;
        stuckReference = null;
        stuckTicks = 0;
        jumpHeld = 0;
        baritone.getInputOverrideHandler().clearAllKeys();
    }

    @Override
    public boolean isActive() {
        return target != null || dismountTicks > 0;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (target == null) {
            // only here to finish dismounting
            return tickDismount();
        }
        if (ctx.player() == null || ctx.world() == null) {
            halt();
            return new PathingCommand(null, PathingCommandType.DEFER);
        }
        Entity vehicle = ctx.player().getVehicle();
        Kind kind = vehicle == null ? null : kindOf(vehicle);
        if (kind == null) {
            logDirect("Ride stopped: you are no longer on a vehicle.");
            halt();
            return new PathingCommand(null, PathingCommandType.DEFER);
        }
        if (vehicle.getControllingPassenger() != ctx.player()) {
            logDirect("Ride stopped: you no longer control the vehicle.");
            halt();
            return new PathingCommand(null, PathingCommandType.DEFER);
        }

        double px = vehicle.getX();
        double pz = vehicle.getZ();
        double dx = target.getX() + 0.5 - px;
        double dz = target.getZ() + 0.5 - pz;
        double dist = Math.sqrt(dx * dx + dz * dz);

        if (dist <= Baritone.settings().rideArrivalRadius.value) {
            logDirect("Arrived.");
            halt();
            if (kind == Kind.PIG || Baritone.settings().rideDismountOnArrival.value) {
                // a pig keeps walking for as long as it's controlled, the only way to stop it is to get off
                dismountTicks = DISMOUNT_TICKS;
                return tickDismount();
            }
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        if (isStuck(px, pz)) {
            logDirect("Ride stopped: not making progress (blocked or boxed in). This is a straight-line steerer, not a pathfinder.");
            halt();
            return new PathingCommand(null, PathingCommandType.DEFER);
        }

        BlockStateInterface bsi = new BlockStateInterface(ctx);
        float desiredYaw = (float) (Mth.atan2(dz, dx) * 180.0D / Math.PI) - 90.0F;
        boolean jump = false;
        Float heading;
        if (kind == Kind.HORSE) {
            jump = updateJump(vehicle, bsi, desiredYaw);
        }
        // while a jump is charging, keep facing the wall we are about to jump over
        heading = jumpHeld > 0 ? Float.valueOf(desiredYaw) : chooseHeading(kind, vehicle, bsi, desiredYaw);

        boolean forward = false;
        boolean left = false;
        boolean right = false;
        if (heading != null) {
            if (kind.isBoat()) {
                float diff = Mth.wrapDegrees(heading - vehicle.getYRot());
                // boats turn clockwise (yaw increases) when "right" is held
                right = diff > 8;
                left = diff < -8;
                forward = Math.abs(diff) < 70;
            } else {
                float current = ctx.player().getYRot();
                float diff = Mth.wrapDegrees(heading - current);
                ctx.player().setYRot(current + Mth.clamp(diff, -MAX_LAND_TURN, MAX_LAND_TURN));
                forward = true;
            }
        }

        // never touch SNEAK here: sneak dismounts
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, forward);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, left);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, right);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, jump);
        baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, false);

        // we never ask for a path, so this only makes sure the pathing behavior stays idle
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    private PathingCommand tickDismount() {
        if (dismountTicks > 0 && ctx.player() != null && ctx.player().getVehicle() != null) {
            baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
            dismountTicks--;
        } else {
            dismountTicks = 0;
            baritone.getInputOverrideHandler().clearAllKeys();
        }
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    /**
     * A jumping mount is charged by holding the jump key, and jumps when it is released. Horses already step up one block
     * by themselves, so this is only for walls up to two blocks high.
     *
     * @return whether the jump key should be held this tick
     */
    private boolean updateJump(Entity vehicle, BlockStateInterface bsi, float desiredYaw) {
        if (jumpHeld > 0) {
            jumpHeld--;
            if (jumpHeld == 0) {
                jumpCooldown = 40;
                return false; // released: the mount jumps now
            }
            return true;
        }
        if (jumpCooldown > 0) {
            jumpCooldown--;
            return false;
        }
        if (!headingFree(Kind.HORSE, vehicle, bsi, desiredYaw) && jumpableAhead(vehicle, bsi, desiredYaw)) {
            jumpHeld = Math.max(2, Baritone.settings().rideJumpChargeTicks.value);
            return true;
        }
        return false;
    }

    /**
     * Is the thing in front of us a wall of at most two blocks, with room to land on top and nothing nasty in it?
     */
    private boolean jumpableAhead(Entity vehicle, BlockStateInterface bsi, float yaw) {
        double rad = Math.toRadians(yaw);
        int bx = Mth.floor(vehicle.getX() - Math.sin(rad) * 1.4);
        int bz = Mth.floor(vehicle.getZ() + Math.cos(rad) * 1.4);
        int by = feetY(bsi, vehicle);
        int height = Math.max(2, (int) Math.ceil(vehicle.getBbHeight()));
        if (MovementHelper.canWalkThrough(bsi, bx, by + 1, bz)) {
            return false; // not a wall: a step, a hazard or a drop
        }
        for (int y = by; y <= by + 1; y++) {
            if (MovementHelper.avoidWalkingInto(bsi.get0(bx, y, bz))) {
                return false;
            }
        }
        for (int y = by + 2; y < by + 2 + height; y++) {
            if (!MovementHelper.canWalkThrough(bsi, bx, y, bz)) {
                return false; // taller than two blocks, or a ceiling
            }
        }
        return true;
    }

    private boolean isStuck(double px, double pz) {
        if (stuckReference == null) {
            stuckReference = new Vec3(px, 0, pz);
            stuckTicks = 0;
            return false;
        }
        if (++stuckTicks < STUCK_TICKS) {
            return false;
        }
        double mx = px - stuckReference.x;
        double mz = pz - stuckReference.z;
        double moved = Math.sqrt(mx * mx + mz * mz);
        stuckReference = new Vec3(px, 0, pz);
        stuckTicks = 0;
        return moved < STUCK_MIN_MOVE;
    }

    /**
     * @return the yaw to head for, or {@code null} if every direction we tried is blocked
     */
    private Float chooseHeading(Kind kind, Entity vehicle, BlockStateInterface bsi, float desiredYaw) {
        for (float deviation : DEVIATIONS) {
            float yaw = desiredYaw + deviation * lastSide;
            if (headingFree(kind, vehicle, bsi, yaw)) {
                if (deviation != 0) {
                    lastSide = Math.signum(deviation) * lastSide;
                }
                return yaw;
            }
        }
        return null;
    }

    private boolean headingFree(Kind kind, Entity vehicle, BlockStateInterface bsi, float yaw) {
        double rad = Math.toRadians(yaw);
        double fx = -Math.sin(rad);
        double fz = Math.cos(rad);
        double near = kind.isBoat() ? 2.5 : 1.4;
        double far = kind.isBoat() ? 5.0 : 2.8;
        double x = vehicle.getX();
        double z = vehicle.getZ();
        if (kind.isBoat()) {
            int y = Mth.floor(vehicle.getY() + 0.1);
            return boatCellOk(bsi, Mth.floor(x + fx * near), y, Mth.floor(z + fz * near))
                    && boatCellOk(bsi, Mth.floor(x + fx * far), y, Mth.floor(z + fz * far));
        }
        return landFree(bsi, vehicle, x + fx * near, z + fz * near, true)
                && landFree(bsi, vehicle, x + fx * far, z + fz * far, false);
    }

    /**
     * A boat can go through water, or over ice (the air block above it)
     */
    private boolean boatCellOk(BlockStateInterface bsi, int x, int y, int z) {
        BlockState state = bsi.get0(x, y, z);
        if (MovementHelper.isWater(state)) {
            return true;
        }
        return state.isAir() && bsi.get0(x, y - 1, z).is(BlockTags.ICE);
    }

    private int feetY(BlockStateInterface bsi, Entity vehicle) {
        int y = Mth.floor(vehicle.getY() + 0.1251);
        if (bsi.get0(Mth.floor(vehicle.getX()), y, Mth.floor(vehicle.getZ())).getBlock() instanceof SlabBlock) {
            y++;
        }
        return y;
    }

    /**
     * Can a land mount be at this column? Horses step up one full block by themselves, so a single
     * block higher is fine, but a taller wall, a hazard (liquid, cactus, fire...) or a drop of more than
     * three blocks is not.
     *
     * @param checkWall whether to check head room too. The far probe only looks for hazards and drops.
     */
    private boolean landFree(BlockStateInterface bsi, Entity vehicle, double x, double z, boolean checkWall) {
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        int by = feetY(bsi, vehicle);
        int height = Math.max(2, (int) Math.ceil(vehicle.getBbHeight()));

        // standing level, or one block up if there's a step
        int standY = MovementHelper.canWalkThrough(bsi, bx, by, bz) ? by : by + 1;
        if (checkWall) {
            for (int y = standY; y < standY + height; y++) {
                if (!MovementHelper.canWalkThrough(bsi, bx, y, bz)) {
                    return false;
                }
            }
        }
        for (int y = standY; y >= standY - 3; y--) {
            BlockState state = bsi.get0(bx, y, bz);
            if (MovementHelper.avoidWalkingInto(state)) {
                return false;
            }
            if (!MovementHelper.canWalkThrough(bsi, bx, y, bz, state)) {
                return true; // found something to stand on
            }
        }
        return false; // nothing to stand on within three blocks: that's a drop
    }

    @Override
    public void onLostControl() {
        // called by #cancel and when another process takes over
        target = null;
        dismountTicks = 0;
        stuckReference = null;
        stuckTicks = 0;
    }

    @Override
    public String displayName0() {
        return target != null ? "Ride to " + target.getX() + ", " + target.getZ() : "Ride (dismounting)";
    }
}
