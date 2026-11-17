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
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.goals.GoalYLevel;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BlockOptionalMeta;
import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.api.utils.Helper;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.cache.WorldScanner;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything after the first step into the Nether: blaze rods from a fortress, ender pearls, eyes of ender, finding the
 * stronghold by throwing them, opening the End portal, and fighting the dragon.
 * <p>
 * Driven by {@link BeatProcess}. Much of this is only known to work on paper, it needs to be watched in game.
 */
final class Endgame implements Helper {

    enum Stage {
        RODS("blaze rods dans le Nether"),
        TO_OVERWORLD("retour à l'Overworld"),
        EYES("yeux d'ender"),
        STRONGHOLD("recherche du stronghold"),
        FILL("portail du End"),
        PREP("préparation du combat"),
        ENTER("entrée dans le End"),
        END("combat du dragon"),
        DONE("terminé");

        final String label;

        Stage(String label) {
            this.label = label;
        }
    }

    private static final PathingCommand DEFER = new PathingCommand(null, PathingCommandType.DEFER);
    private static final PathingCommand HOLD = new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);

    private static final int RODS_WANTED = 8;
    private static final int EYES_WANTED = 14;
    private static final int PORTAL_FRAMES = 12;

    private final Baritone baritone;
    private final IPlayerContext ctx;
    private final Survival survival;

    private Stage stage = Stage.RODS;
    private int ticks;

    // known places
    private BlockPos netherArrival;
    private BlockPos fortress;
    private BlockPos stronghold; // one of the portal frames
    private int lastScan = -1000;

    // entering portals
    private int enterTicks;

    // eyes of ender
    private int throwCooldownUntil;
    private int throwHoldTicks;
    private Vec3 eyeFirst;
    private int eyeSeenTicks;
    private double heading = Double.NaN; // degrees, as yaw
    private int hopsSinceThrow;
    private int closeThrows;

    // portal room
    private int fillCooldownUntil;
    private int fillAttempts;

    // the End
    private boolean dragonSeen;
    private int shootTicks;
    private int shootCooldownUntil;
    private final Map<Integer, Integer> volleysAtCrystal = new HashMap<>();
    private int currentCrystal = -1;
    private int endLoopFailures;

    Endgame(Baritone baritone, Survival survival) {
        this.baritone = baritone;
        this.ctx = baritone.getPlayerContext();
        this.survival = survival;
    }

    void reset() {
        stage = Stage.RODS;
        ticks = 0;
        netherArrival = null;
        fortress = null;
        stronghold = null;
        lastScan = -1000;
        enterTicks = 0;
        throwCooldownUntil = 0;
        throwHoldTicks = 0;
        eyeFirst = null;
        eyeSeenTicks = 0;
        heading = Double.NaN;
        hopsSinceThrow = 0;
        closeThrows = 0;
        fillCooldownUntil = 0;
        fillAttempts = 0;
        dragonSeen = false;
        shootTicks = 0;
        shootCooldownUntil = 0;
        volleysAtCrystal.clear();
        currentCrystal = -1;
    }

    Stage stage() {
        return stage;
    }

    String describe() {
        return stage.label;
    }

    boolean done() {
        return stage == Stage.DONE;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private void say(String message) {
        logDirect("Beat: " + message);
    }

    private void goTo(BlockPos pos, int range) {
        baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(pos, range));
    }

    private List<BlockPos> scan(int max, Block... blocks) {
        BlockOptionalMeta[] metas = new BlockOptionalMeta[blocks.length];
        for (int i = 0; i < blocks.length; i++) {
            metas[i] = new BlockOptionalMeta(blocks[i]);
        }
        return WorldScanner.INSTANCE.scanChunkRadius(ctx, new BlockOptionalMetaLookup(metas), max, 64, 64);
    }

    private boolean pathing() {
        return baritone.getCustomGoalProcess().isActive() || baritone.getExploreProcess().isActive() || baritone.getMineProcess().isActive();
    }

    private int count(net.minecraft.world.item.Item item) {
        return survival.countItem(item);
    }

    private void aimAt(LocalPlayer p, Vec3 target) {
        Rotation rotation = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), target, ctx.playerRotations());
        p.setYRot(rotation.getYaw());
        p.setXRot(rotation.getPitch());
    }

    /**
     * Gets inside a portal (nether or end): go next to it, then walk into it
     */
    private PathingCommand walkInto(LocalPlayer p, BlockPos portalBlock) {
        enterTicks++;
        Vec3 center = Vec3.atBottomCenterOf(portalBlock);
        double dx = center.x - p.getX();
        double dz = center.z - p.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal > 3.5 || Math.abs(center.y - p.getY()) > 3) {
            baritone.getInputOverrideHandler().clearAllKeys();
            if (!pathing()) {
                goTo(portalBlock, 2);
            }
            return DEFER;
        }
        if (baritone.getCustomGoalProcess().isActive()) {
            baritone.getCustomGoalProcess().onLostControl();
        }
        aimAt(p, new Vec3(center.x, ctx.playerHead().y, center.z));
        p.setXRot(0);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, horizontal > 0.3);
        return HOLD;
    }

    // ------------------------------------------------------------------------------------------------- tick

    /**
     * @return what to do this tick, or {@code null} if the stage can't be played in this dimension (the caller then
     * goes back to its own steps)
     */
    PathingCommand tick() {
        LocalPlayer p = ctx.player();
        ticks++;
        Level level = ctx.world();
        boolean nether = level.dimension() == Level.NETHER;
        boolean end = level.dimension() == Level.END;

        if (nether && netherArrival == null) {
            netherArrival = ctx.playerFeet();
        }
        if (ctx.minecraft().screen != null && ctx.minecraft().screen.getClass().getSimpleName().contains("Win")) {
            stage = Stage.DONE;
        }
        if (end && stage.ordinal() < Stage.END.ordinal()) {
            stage = Stage.END;
        }

        switch (stage) {
            case RODS:
                return rods(p, nether);
            case TO_OVERWORLD:
                return toOverworld(p, nether);
            case EYES:
                return eyes(p);
            case STRONGHOLD:
                return findStronghold(p);
            case FILL:
                return fill(p);
            case PREP:
                return prep(p);
            case ENTER:
                return enter(p);
            case END:
                return fightDragon(p, end);
            default:
                return DEFER;
        }
    }

    // -------------------------------------------------------------------------------------- 1. blaze rods

    private PathingCommand rods(LocalPlayer p, boolean nether) {
        if (!nether) {
            return null; // the portal part of BeatProcess brings us there
        }
        if (count(Items.BLAZE_ROD) >= RODS_WANTED) {
            say("assez de blaze rods (" + count(Items.BLAZE_ROD) + "), je rentre.");
            stage = Stage.TO_OVERWORLD;
            return HOLD;
        }
        if (survival.delegating()) {
            // keep an eye out for the fortress while exploring
            if (fortress == null && ticks - lastScan > 80) {
                lastScan = ticks;
                List<BlockPos> bricks = scan(1, Blocks.NETHER_BRICKS, Blocks.NETHER_BRICK_FENCE);
                if (!bricks.isEmpty()) {
                    fortress = bricks.get(0);
                    say("forteresse repérée en " + fortress.getX() + " " + fortress.getY() + " " + fortress.getZ() + ".");
                    baritone.getExploreProcess().onLostControl();
                    baritone.getCustomGoalProcess().onLostControl();
                    return HOLD;
                }
            }
            return DEFER;
        }

        boolean blazeNear = false;
        for (Entity e : ctx.entities()) {
            if (e instanceof Blaze && e.isAlive() && p.distanceToSqr(e) < 40 * 40) {
                blazeNear = true;
                break;
            }
        }
        if (blazeNear) {
            PathingCommand c = survival.acquire(Items.BLAZE_ROD, RODS_WANTED);
            if (survival.failure() != null) {
                survival.clearFailure();
            }
            if (c != null) {
                return c;
            }
        }
        if (fortress == null) {
            List<BlockPos> bricks = scan(1, Blocks.NETHER_BRICKS, Blocks.NETHER_BRICK_FENCE);
            if (!bricks.isEmpty()) {
                fortress = bricks.get(0);
                say("forteresse repérée en " + fortress.getX() + " " + fortress.getY() + " " + fortress.getZ() + ".");
            }
        }
        if (fortress == null) {
            say("je cherche une forteresse du Nether.");
            baritone.getExploreProcess().explore(p.getBlockX(), p.getBlockZ());
            return DEFER;
        }
        if (p.distanceToSqr(fortress.getX(), fortress.getY(), fortress.getZ()) > 12 * 12) {
            goTo(fortress, 6);
            return DEFER;
        }
        // in the fortress and no blaze in sight: look around, spawners are somewhere
        List<BlockPos> spawners = scan(1, Blocks.SPAWNER);
        if (!spawners.isEmpty()) {
            BlockPos spawner = spawners.get(0);
            if (p.distanceToSqr(spawner.getX(), spawner.getY(), spawner.getZ()) > 8 * 8) {
                goTo(spawner, 6);
                return DEFER;
            }
            return HOLD; // wait here, blazes come to us
        }
        baritone.getExploreProcess().explore(fortress.getX(), fortress.getZ());
        return DEFER;
    }

    // ------------------------------------------------------------------------------------- 2. go home

    private PathingCommand toOverworld(LocalPlayer p, boolean nether) {
        if (!nether) {
            say("de retour dans l'Overworld.");
            stage = Stage.EYES;
            return HOLD;
        }
        if (survival.delegating() && enterTicks == 0) {
            return DEFER;
        }
        List<BlockPos> portals = scan(1, Blocks.NETHER_PORTAL);
        if (portals.isEmpty()) {
            if (netherArrival != null && !pathing()) {
                goTo(netherArrival, 2);
            }
            return DEFER;
        }
        return walkInto(p, portals.get(0));
    }

    // ------------------------------------------------------------------------------------ 3. eyes of ender

    private PathingCommand eyes(LocalPlayer p) {
        if (count(Items.ENDER_EYE) >= EYES_WANTED) {
            say("j'ai " + count(Items.ENDER_EYE) + " yeux d'ender, je cherche le stronghold.");
            stage = Stage.STRONGHOLD;
            return HOLD;
        }
        if (survival.delegating()) {
            return DEFER;
        }
        PathingCommand c = survival.acquire(Items.ENDER_EYE, EYES_WANTED);
        if (survival.failure() != null) {
            say(survival.failure() + " (je réessaie).");
            survival.clearFailure();
            return HOLD;
        }
        return c != null ? c : HOLD;
    }

    // -------------------------------------------------------------------------------- 4. the stronghold

    private PathingCommand findStronghold(LocalPlayer p) {
        // the chunk data of the stronghold arrives as soon as we are within view distance, even though it is underground
        if (ticks - lastScan > 40) {
            lastScan = ticks;
            List<BlockPos> frames = scan(1, Blocks.END_PORTAL_FRAME);
            if (!frames.isEmpty()) {
                stronghold = frames.get(0);
                say("stronghold trouvé en " + stronghold.getX() + " " + stronghold.getY() + " " + stronghold.getZ() + ".");
                baritone.getCustomGoalProcess().onLostControl();
                stage = Stage.FILL;
                return HOLD;
            }
        }

        EyeOfEnder eye = null;
        for (Entity e : ctx.entities()) {
            if (e instanceof EyeOfEnder && e.isAlive()) {
                eye = (EyeOfEnder) e;
                break;
            }
        }
        if (eye != null) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
            if (eyeFirst == null) {
                eyeFirst = eye.position();
                eyeSeenTicks = 0;
            }
            eyeSeenTicks++;
            double dx = eye.getX() - eyeFirst.x;
            double dz = eye.getZ() - eyeFirst.z;
            double flown = Math.sqrt(dx * dx + dz * dz);
            if (flown > 8 || eyeSeenTicks > 40) {
                if (flown < 3) {
                    closeThrows++; // it hardly moves: we are on top of it
                } else {
                    closeThrows = 0;
                    heading = Math.toDegrees(Math.atan2(-dx, dz));
                }
            }
            return HOLD;
        }
        eyeFirst = null;
        eyeSeenTicks = 0;

        if (survival.delegating()) {
            return DEFER;
        }
        // an eye that fell: pick it up
        PathingCommand forage = survival.foraging();
        if (forage != null) {
            return forage;
        }

        if (closeThrows >= 2) {
            // the eyes go straight down: the room is right under us, dig
            say("le stronghold est juste sous moi, je creuse.");
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalYLevel(Math.max(ctx.world().getMinBuildHeight() + 5, 25)));
            closeThrows = 0;
            return DEFER;
        }

        if (count(Items.ENDER_EYE) < 3) {
            stage = Stage.EYES;
            return HOLD;
        }

        // travel along the last known direction, then throw again
        if (!Double.isNaN(heading) && hopsSinceThrow == 0) {
            double yaw = Math.toRadians(heading);
            int tx = (int) (p.getX() - Math.sin(yaw) * 70);
            int tz = (int) (p.getZ() + Math.cos(yaw) * 70);
            hopsSinceThrow = 1;
            say("j'avance de 70 blocs vers le stronghold.");
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalXZ(tx, tz));
            return DEFER;
        }
        hopsSinceThrow = 0;

        // throw an eye
        if (ticks < throwCooldownUntil) {
            return HOLD;
        }
        if (!baritone.getInventoryBehavior().throwaway(true, s -> s.is(Items.ENDER_EYE), true)) {
            stage = Stage.EYES;
            return HOLD;
        }
        throwHoldTicks++;
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, throwHoldTicks <= 3);
        if (throwHoldTicks > 4) {
            throwHoldTicks = 0;
            throwCooldownUntil = ticks + 30;
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
        }
        return HOLD;
    }

    // -------------------------------------------------------------------------- 5. open the end portal

    private PathingCommand fill(LocalPlayer p) {
        List<BlockPos> frames = scan(PORTAL_FRAMES + 4, Blocks.END_PORTAL_FRAME);
        List<BlockPos> portal = scan(1, Blocks.END_PORTAL);
        if (!portal.isEmpty()) {
            say("le portail du End est ouvert !");
            baritone.getCustomGoalProcess().onLostControl();
            stage = Stage.PREP;
            return HOLD;
        }
        if (frames.isEmpty()) {
            if (stronghold != null) {
                if (!pathing()) {
                    goTo(stronghold, 2);
                }
                return DEFER;
            }
            stage = Stage.STRONGHOLD;
            return HOLD;
        }
        BlockPos target = null;
        for (BlockPos pos : frames) {
            BlockState state = ctx.world().getBlockState(pos);
            if (state.is(Blocks.END_PORTAL_FRAME) && !state.getValue(EndPortalFrameBlock.HAS_EYE)) {
                if (target == null || p.distanceToSqr(pos.getX(), pos.getY(), pos.getZ()) < p.distanceToSqr(target.getX(), target.getY(), target.getZ())) {
                    target = pos;
                }
            }
        }
        if (target == null) {
            return HOLD; // all filled, the portal is about to show up
        }
        if (count(Items.ENDER_EYE) == 0) {
            stage = Stage.EYES;
            return HOLD;
        }
        if (p.getEyePosition().distanceTo(Vec3.atCenterOf(target)) > 3.6) {
            if (!pathing()) {
                goTo(target.above(), 2);
            }
            return DEFER;
        }
        if (baritone.getCustomGoalProcess().isActive()) {
            baritone.getCustomGoalProcess().onLostControl();
        }
        if (ticks < fillCooldownUntil) {
            return HOLD;
        }
        if (++fillAttempts > 60) {
            say("je n'arrive pas à remplir le portail, je m'arrête.");
            stage = Stage.DONE;
            return HOLD;
        }
        baritone.getInventoryBehavior().throwaway(true, s -> s.is(Items.ENDER_EYE), true);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(target).add(0, 0.5, 0), Direction.UP, target, false);
        ctx.playerController().processRightClickBlock(p, ctx.world(), InteractionHand.MAIN_HAND, hit);
        p.swing(InteractionHand.MAIN_HAND);
        fillCooldownUntil = ticks + 8;
        return HOLD;
    }

    // ------------------------------------------------------------------------------------------ 6. prepare

    private PathingCommand prep(LocalPlayer p) {
        if (survival.delegating()) {
            return DEFER;
        }
        Object[][] gear = {
                {Items.DIAMOND_SWORD, 1},
                {Items.BOW, 1},
                {Items.ARROW, 40},
                {Items.COBBLESTONE, 64},
                {Items.COOKED_BEEF, 16},
        };
        for (Object[] want : gear) {
            PathingCommand c = survival.acquire((net.minecraft.world.item.Item) want[0], (Integer) want[1]);
            if (survival.failure() != null) {
                say(survival.failure() + " — je continue sans.");
                survival.clearFailure();
                continue;
            }
            if (c != null) {
                return c;
            }
        }
        stage = Stage.ENTER;
        enterTicks = 0;
        return HOLD;
    }

    private PathingCommand enter(LocalPlayer p) {
        if (ctx.world().dimension() == Level.END) {
            stage = Stage.END;
            return HOLD;
        }
        List<BlockPos> portal = scan(1, Blocks.END_PORTAL);
        if (portal.isEmpty()) {
            stage = Stage.FILL;
            return HOLD;
        }
        if (enterTicks > 20 * 90) {
            say("je n'arrive pas à entrer dans le portail du End.");
            stage = Stage.DONE;
            return HOLD;
        }
        return walkInto(p, portal.get(0));
    }

    // -------------------------------------------------------------------------------------- 7. the dragon

    private EnderDragon dragon() {
        for (Entity e : ctx.entities()) {
            if (e instanceof EnderDragon && e.isAlive()) {
                return (EnderDragon) e;
            }
        }
        return null;
    }

    private EndCrystal nearestCrystal(LocalPlayer p) {
        EndCrystal best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : ctx.entities()) {
            if (e instanceof EndCrystal && e.isAlive()) {
                int volleys = volleysAtCrystal.getOrDefault(e.getId(), 0);
                if (volleys >= 6) {
                    continue; // caged or out of reach, forget it
                }
                double d = p.distanceToSqr(e);
                if (d < bestDist) {
                    bestDist = d;
                    best = (EndCrystal) e;
                }
            }
        }
        return best;
    }

    /**
     * The pitch (degrees, negative is up) that makes a fully drawn arrow from here pass closest to {@code target}
     */
    private static float solvePitch(Vec3 from, Vec3 target, float yaw) {
        double yawRad = Math.toRadians(yaw);
        float bestPitch = 0;
        double bestMiss = Double.MAX_VALUE;
        for (float pitch = -85; pitch <= 45; pitch += 0.25F) {
            double pitchRad = Math.toRadians(pitch);
            double vx = -Math.sin(yawRad) * Math.cos(pitchRad) * 3.0;
            double vy = -Math.sin(pitchRad) * 3.0;
            double vz = Math.cos(yawRad) * Math.cos(pitchRad) * 3.0;
            double x = from.x;
            double y = from.y;
            double z = from.z;
            double miss = Double.MAX_VALUE;
            for (int t = 0; t < 160; t++) {
                x += vx;
                y += vy;
                z += vz;
                vx *= 0.99;
                vy = vy * 0.99 - 0.05;
                vz *= 0.99;
                double dx = x - target.x;
                double dy = y - target.y;
                double dz = z - target.z;
                miss = Math.min(miss, dx * dx + dy * dy + dz * dz);
                if (vy < 0 && y < target.y - 5) {
                    break;
                }
            }
            if (miss < bestMiss) {
                bestMiss = miss;
                bestPitch = pitch;
            }
        }
        return bestPitch;
    }

    /**
     * Draws the bow and lets go at the target. Returns true while it is busy shooting.
     */
    private boolean shoot(LocalPlayer p, Vec3 target) {
        if (!baritone.getInventoryBehavior().throwaway(true, s -> s.is(Items.BOW), true)) {
            return false;
        }
        Vec3 eye = ctx.playerHead();
        float yaw = (float) Math.toDegrees(Math.atan2(-(target.x - eye.x), target.z - eye.z));
        float pitch = solvePitch(eye, target, yaw);
        p.setYRot(yaw);
        p.setXRot(pitch);
        shootTicks++;
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, shootTicks <= 24);
        if (shootTicks > 26) {
            shootTicks = 0;
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
            return false;
        }
        return true;
    }

    private PathingCommand fightDragon(LocalPlayer p, boolean end) {
        if (!end) {
            return null;
        }
        if (survival.delegating() && baritone.getCustomGoalProcess().isActive() && ticks % 40 != 0) {
            return DEFER;
        }
        EnderDragon dragon = dragon();
        if (dragon != null) {
            dragonSeen = true;
        }
        if (dragonSeen && dragon == null) {
            // dead: the exit portal appears at the centre
            List<BlockPos> exit = scan(1, Blocks.END_PORTAL);
            if (exit.isEmpty()) {
                if (ticks % 100 == 0) {
                    say("le dragon est mort. J'attends le portail de sortie.");
                }
                return HOLD;
            }
            say("le dragon est mort ! J'entre dans le portail de sortie : générique de fin.");
            stage = Stage.ENTER; // walkInto handles it, and the credits screen ends it
            return HOLD;
        }

        // 1. the crystals, with the bow
        EndCrystal crystal = nearestCrystal(p);
        if (crystal != null && count(Items.ARROW) > 0 && count(Items.BOW) > 0) {
            double dist = Math.sqrt(p.distanceToSqr(crystal));
            if (currentCrystal != crystal.getId()) {
                currentCrystal = crystal.getId();
                say("je tire sur un cristal.");
            }
            if (dist > 26 || !p.hasLineOfSight(crystal)) {
                // get closer, on the ground, and look again
                if (!baritone.getCustomGoalProcess().isActive() || ticks % 60 == 0) {
                    double dx = crystal.getX() - p.getX();
                    double dz = crystal.getZ() - p.getZ();
                    double len = Math.max(1, Math.sqrt(dx * dx + dz * dz));
                    int gx = (int) (crystal.getX() - dx / len * 16);
                    int gz = (int) (crystal.getZ() - dz / len * 16);
                    baritone.getCustomGoalProcess().setGoalAndPath(new GoalXZ(gx, gz));
                }
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
                shootTicks = 0;
                return DEFER;
            }
            if (baritone.getCustomGoalProcess().isActive()) {
                baritone.getCustomGoalProcess().onLostControl();
            }
            if (ticks < shootCooldownUntil) {
                return HOLD;
            }
            Vec3 target = crystal.position().add(0, 1.0, 0);
            boolean busy = shoot(p, target);
            if (!busy) {
                shootCooldownUntil = ticks + 8;
                volleysAtCrystal.merge(crystal.getId(), 1, Integer::sum);
            }
            return HOLD;
        }

        // 2. the dragon itself
        if (dragon != null) {
            Vec3 head = dragon.head.getBoundingBox().getCenter();
            double headDist = ctx.playerHead().distanceTo(head);
            if (headDist <= 3.2) {
                aimAt(p, head);
                if (p.getAttackStrengthScale(0.5F) >= 1.0F) {
                    ctx.minecraft().gameMode.attack(p, dragon.head);
                    p.swing(InteractionHand.MAIN_HAND);
                }
                baritone.getInventoryBehavior().throwaway(true, s -> s.getItem() instanceof net.minecraft.world.item.SwordItem, true);
                return HOLD;
            }
            if (headDist < 55 && count(Items.ARROW) > 0 && ticks >= shootCooldownUntil && p.hasLineOfSight(dragon)) {
                if (baritone.getCustomGoalProcess().isActive()) {
                    baritone.getCustomGoalProcess().onLostControl();
                }
                if (!shoot(p, head)) {
                    shootCooldownUntil = ticks + 10;
                }
                return HOLD;
            }
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
            // wait under the fountain, where the dragon comes to perch
            if (p.distanceToSqr(0, p.getY(), 0) > 10 * 10 && !baritone.getCustomGoalProcess().isActive()) {
                baritone.getCustomGoalProcess().setGoalAndPath(new GoalXZ(0, 3));
            }
            return DEFER;
        }

        // nothing in sight yet (just arrived): go towards the middle of the island
        if (!baritone.getCustomGoalProcess().isActive()) {
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalXZ(0, 40));
        }
        return DEFER;
    }
}
