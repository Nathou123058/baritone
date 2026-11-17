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
import baritone.api.process.IBeatProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.schematic.AbstractSchematic;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Plays survival Minecraft on its own towards beating the game.
 * <p>
 * The item part is data driven: a list of things to own ({@link #WANTS}) is worked through in order by
 * {@link Survival}, which knows how to get any item from the game's recipes. After that come the stages that need
 * actual game knowledge (the portal, the Nether, ...).
 */
public final class BeatProcess extends BaritoneProcessHelper implements IBeatProcess {

    private static final PathingCommand DEFER = new PathingCommand(null, PathingCommandType.DEFER);
    private static final PathingCommand HOLD = new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);

    private static final int RETRY_FAILED_AFTER = 20 * 90;

    /**
     * What to own before going to the Nether, in order. Each entry is simply acquired; ingots are asked for in batches
     * so that the furnace is used once for many items.
     */
    private static final Object[][] WANTS = {
            {Items.WOODEN_PICKAXE, 1},
            {Items.STONE_PICKAXE, 1},
            {Items.STONE_SWORD, 1},
            {Items.FURNACE, 1},
            {Items.IRON_INGOT, 12}, // pickaxe 3, sword 2, bucket 3, flint and steel 1, shield 1 (+ a spare)
            {Items.IRON_PICKAXE, 1},
            {Items.IRON_SWORD, 1},
            {Items.BUCKET, 1},
            {Items.SHIELD, 1},
            {Items.FLINT_AND_STEEL, 1},
            {Items.COOKED_BEEF, 8},
            {Items.IRON_INGOT, 24}, // the armor
            {Items.IRON_CHESTPLATE, 1},
            {Items.IRON_LEGGINGS, 1},
            {Items.IRON_HELMET, 1},
            {Items.IRON_BOOTS, 1},
            {Items.DIAMOND_PICKAXE, 1},
            {Items.OBSIDIAN, 10},
    };

    private final Survival survival;
    private final Endgame endgame;
    private boolean endgameStarted;

    private boolean running;
    private boolean previousAllowInventory;
    private String step = "";
    private int ticks;

    /**
     * item index in WANTS -> tick at which we try it again
     */
    private final Map<Integer, Integer> failedUntil = new HashMap<>();

    // the nether portal
    private BlockPos portalOrigin; // the frame's (0, 0) corner, it is 4 wide (x) and 5 high
    private int ignitionAttempts;
    private int ignitionCooldownUntil;
    private int enterTicks;
    private boolean arrivedInNether;

    public BeatProcess(Baritone baritone) {
        super(baritone);
        this.survival = new Survival(baritone);
        this.endgame = new Endgame(baritone, survival);
    }

    // -------------------------------------------------------------------------------------------- public API

    @Override
    public void start() {
        resetState();
        previousAllowInventory = Baritone.settings().allowInventory.value;
        // needed to pull tools and blocks from the main inventory to the hotbar
        Baritone.settings().allowInventory.value = true;
        running = true;
        logDirect("Beat: c'est parti. Je fabrique tout l'équipement (outils, armure en fer, bouclier, seau, briquet, pioche en diamant), "
                + "je récupère l'obsidienne, je construis et j'allume un portail du Nether et j'y entre. #beat stop ou #cancel pour arrêter.");
    }

    @Override
    public void stop() {
        stopInternal();
        logDirect("Beat: arrêté.");
    }

    @Override
    public String status() {
        return running ? step : "inactif";
    }

    private void stopInternal() {
        if (running) {
            Baritone.settings().allowInventory.value = previousAllowInventory;
        }
        running = false;
        survival.shutdown();
        resetState();
    }

    private void resetState() {
        ticks = 0;
        step = "";
        failedUntil.clear();
        portalOrigin = null;
        ignitionAttempts = 0;
        ignitionCooldownUntil = 0;
        enterTicks = 0;
        arrivedInNether = false;
        endgameStarted = false;
        endgame.reset();
        survival.reset();
    }

    @Override
    public boolean isActive() {
        return running;
    }

    @Override
    public double priority() {
        // above everything else: it decides when to delegate to them and when to take over
        return 10;
    }

    @Override
    public void onLostControl() {
        // called by #cancel
        if (running) {
            stopInternal();
            logDirect("Beat: arrêté.");
        }
    }

    @Override
    public String displayName0() {
        return "Beat: " + step;
    }

    private void setStep(String newStep) {
        if (!newStep.equals(step)) {
            step = newStep;
            logDirect("Beat: " + newStep);
        }
    }

    private PathingCommand finish(String message) {
        logDirect("Beat: " + message);
        stopInternal();
        return DEFER;
    }

    // --------------------------------------------------------------------------------------------- main tick

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        LocalPlayer p = ctx.player();
        if (p == null || ctx.world() == null) {
            stopInternal();
            return DEFER;
        }
        if (p.isDeadOrDying()) {
            return finish("le joueur est mort, arrêt.");
        }
        ticks++;
        survival.beginTick();

        PathingCommand basics = survival.basics();
        if (basics != null) {
            return basics;
        }
        if (ctx.world().dimension() == Level.NETHER && !endgameStarted) {
            endgameStarted = true;
            logDirect("Beat: Nether atteint. Suite : blaze rods, perles d'ender, stronghold, portail du End, dragon.");
        }
        if (endgameStarted) {
            PathingCommand c = endgame.tick();
            if (endgame.done()) {
                return finish("terminé (fin de la partie, ou arrêt de l'étape finale).");
            }
            setStep(endgame.describe());
            if (c != null) {
                return c;
            }
        }
        if (survival.delegating() || baritone.getBuilderProcess().isActive()) {
            return DEFER;
        }
        PathingCommand forage = survival.foraging();
        if (forage != null) {
            return forage;
        }
        return nextStep(p);
    }

    // ------------------------------------------------------------------------------------------ milestones

    private PathingCommand nextStep(LocalPlayer p) {
        for (int i = 0; i < WANTS.length; i++) {
            Item item = (Item) WANTS[i][0];
            int count = (Integer) WANTS[i][1];
            Integer retryAt = failedUntil.get(i);
            if (retryAt != null && ticks < retryAt) {
                continue;
            }
            PathingCommand command = survival.acquire(item, count);
            if (survival.failure() != null) {
                logDirect("Beat: " + survival.failure() + " (je réessaierai plus tard).");
                failedUntil.put(i, ticks + RETRY_FAILED_AFTER);
                survival.clearFailure();
                return HOLD;
            }
            if (command != null) {
                return command;
            }
        }
        if (!failedUntil.isEmpty()) {
            // not everything could be made: wait for the retries
            setStep("j'attends pour réessayer ce qui a échoué");
            return HOLD;
        }
        return portal(p);
    }

    // ---------------------------------------------------------------------------------------------- portal

    /**
     * The frame of a nether portal: 4 wide, 5 high, in the x/y plane. Only the 10 blocks that matter are asked for,
     * the corners are left as they are.
     */
    private static final class PortalSchematic extends AbstractSchematic {

        PortalSchematic() {
            super(4, 5, 1);
        }

        @Override
        public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
            boolean frame = ((y == 0 || y == 4) && (x == 1 || x == 2)) || ((x == 0 || x == 3) && y >= 1 && y <= 3);
            boolean inside = x >= 1 && x <= 2 && y >= 1 && y <= 3;
            if (frame) {
                return Blocks.OBSIDIAN.defaultBlockState();
            }
            if (inside) {
                return Blocks.AIR.defaultBlockState();
            }
            return current;
        }
    }

    private boolean frameComplete() {
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 5; y++) {
                boolean frame = ((y == 0 || y == 4) && (x == 1 || x == 2)) || ((x == 0 || x == 3) && y >= 1 && y <= 3);
                if (frame && !ctx.world().getBlockState(portalOrigin.offset(x, y, 0)).is(Blocks.OBSIDIAN)) {
                    return false;
                }
            }
        }
        return true;
    }

    private BlockPos chooseSite() {
        BlockPos feet = ctx.playerFeet();
        int[][] offsets = {{-1, 3}, {-1, -3}, {-5, 0}, {3, 0}, {-1, 6}, {-1, -6}, {-8, 0}, {6, 0}};
        for (int[] offset : offsets) {
            BlockPos origin = feet.offset(offset[0], 0, offset[1]);
            boolean ok = true;
            for (int x = 0; x < 4 && ok; x++) {
                BlockPos below = origin.offset(x, -1, 0);
                BlockState ground = ctx.world().getBlockState(below);
                ok = ground.isFaceSturdy(ctx.world(), below, Direction.UP) && ground.getFluidState().isEmpty();
            }
            for (int x = 0; x < 4 && ok; x++) {
                for (int y = 0; y < 5 && ok; y++) {
                    ok = ctx.world().getBlockState(origin.offset(x, y, 0)).getFluidState().isEmpty();
                }
            }
            if (ok) {
                return origin;
            }
        }
        return feet.offset(-1, 0, 3);
    }

    private PathingCommand portal(LocalPlayer p) {
        if (portalOrigin == null) {
            portalOrigin = chooseSite();
        }
        BlockPos inside = portalOrigin.offset(1, 1, 0);
        boolean lit = ctx.world().getBlockState(inside).is(Blocks.NETHER_PORTAL);

        if (!lit && !frameComplete()) {
            setStep("je construis le cadre du portail");
            baritone.getBuilderProcess().build("portail du nether", new PortalSchematic(), portalOrigin);
            return DEFER;
        }
        if (!lit) {
            setStep("j'allume le portail");
            BlockPos standing = portalOrigin.offset(1, 0, 2);
            BlockPos support = portalOrigin.offset(1, 0, 0);
            if (p.getEyePosition().distanceTo(Vec3.atCenterOf(support)) > 3.8) {
                baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(standing, 1));
                return DEFER;
            }
            if (ticks < ignitionCooldownUntil) {
                return HOLD;
            }
            if (++ignitionAttempts > 8) {
                return finish("je n'arrive pas à allumer le portail, je m'arrête. (Allume-le toi-même avec un briquet.)");
            }
            if (!baritone.getInventoryBehavior().throwaway(true, s -> s.is(Items.FLINT_AND_STEEL), true)) {
                return finish("je n'ai plus de briquet.");
            }
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(support).add(0, 0.5, 0), Direction.UP, support, false);
            ctx.playerController().processRightClickBlock(p, ctx.world(), InteractionHand.MAIN_HAND, hit);
            p.swing(InteractionHand.MAIN_HAND);
            ignitionCooldownUntil = ticks + 15;
            return HOLD;
        }

        // lit: walk in and wait
        setStep("j'entre dans le portail");
        enterTicks++;
        if (enterTicks > 20 * 40) {
            return finish("le portail ne m'emmène pas, je m'arrête.");
        }
        BlockPos feet = ctx.playerFeet();
        boolean inPortal = ctx.world().getBlockState(feet).is(Blocks.NETHER_PORTAL);
        if (inPortal) {
            baritone.getInputOverrideHandler().clearAllKeys();
            return HOLD; // the teleport takes about 4 seconds
        }
        Vec3 target = new Vec3(portalOrigin.getX() + 2.0, p.getY(), portalOrigin.getZ() + 0.5);
        Rotation rotation = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), new Vec3(target.x, ctx.playerHead().y, target.z), ctx.playerRotations());
        p.setYRot(rotation.getYaw());
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
        return HOLD;
    }
}
