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
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Plays survival Minecraft by itself, towards beating the game, with no cheats.
 * <p>
 * This is an orchestrator that sits above the other processes. It looks at the inventory to decide what the next
 * milestone is, and either delegates (mining, walking) to the existing Baritone processes, or takes over for what they
 * can't do: fighting, eating, crafting by clicking in the inventory, placing blocks, using a furnace.
 * <p>
 * Implemented so far (stage 1, "iron age"): wood, crafting table, wooden / stone / iron tools, furnace, bucket.
 */
public final class BeatProcess extends BaritoneProcessHelper implements IBeatProcess {

    // ------------------------------------------------------------------------------------------------ tuning

    private static final double FIGHT_START_SQ = 4.5 * 4.5;
    private static final double FIGHT_KEEP_SQ = 8.0 * 8.0;
    private static final double ATTACK_REACH = 3.0;
    private static final double CONTAINER_REACH = 4.4;
    private static final int HUNGER_EAT_AT = 14;
    private static final int HUNGER_HUNT_AT = 12;
    private static final int JOB_TIMEOUT = 60;
    private static final int TICKS_PER_SMELT = 200;

    // --------------------------------------------------------------------------------------------- item tests

    private static final Predicate<ItemStack> LOG = s -> s.is(ItemTags.LOGS);
    private static final Predicate<ItemStack> PLANKS = s -> s.is(ItemTags.PLANKS);
    private static final Predicate<ItemStack> STICK = s -> s.is(Items.STICK);
    private static final Predicate<ItemStack> COBBLE = s -> s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE) || s.is(Items.BLACKSTONE);
    private static final Predicate<ItemStack> IRON = s -> s.is(Items.IRON_INGOT);
    private static final Predicate<ItemStack> RAW_IRON = s -> s.is(Items.RAW_IRON);
    private static final Predicate<ItemStack> COAL = s -> s.is(Items.COAL) || s.is(Items.CHARCOAL);
    private static final Predicate<ItemStack> FUEL = s -> LOG.test(s) || PLANKS.test(s) || COAL.test(s);

    private static final Block[] LOG_BLOCKS = {
            Blocks.OAK_LOG, Blocks.SPRUCE_LOG, Blocks.BIRCH_LOG, Blocks.JUNGLE_LOG,
            Blocks.ACACIA_LOG, Blocks.DARK_OAK_LOG, Blocks.MANGROVE_LOG, Blocks.CHERRY_LOG
    };

    private static final Set<Item> BAD_FOOD = new HashSet<>(Arrays.asList(
            Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO, Items.PUFFERFISH, Items.CHORUS_FRUIT,
            Items.SUSPICIOUS_STEW, Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE
    ));

    private static boolean isGoodFood(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem().isEdible() && !BAD_FOOD.contains(stack.getItem());
    }

    // ---------------------------------------------------------------------------------------------- recipes

    /**
     * A shaped recipe. {@code cells} is row major ({@code size * size} entries), {@code null} meaning empty.
     */
    private static final class Recipe {
        final String name;
        final int size;
        final Predicate<ItemStack> result;
        final List<Predicate<ItemStack>> cells;

        Recipe(String name, int size, Predicate<ItemStack> result, List<Predicate<ItemStack>> cells) {
            this.name = name;
            this.size = size;
            this.result = result;
            this.cells = cells;
        }
    }

    @SafeVarargs
    private static Recipe recipe(String name, int size, Predicate<ItemStack> result, Predicate<ItemStack>... cells) {
        return new Recipe(name, size, result, Arrays.asList(cells));
    }

    private static Predicate<ItemStack> is(Item item) {
        return s -> s.is(item);
    }

    private static final Recipe PLANKS_R = recipe("planches", 2, PLANKS, LOG, null, null, null);
    private static final Recipe STICKS_R = recipe("batons", 2, STICK, PLANKS, null, PLANKS, null);
    private static final Recipe TABLE_R = recipe("établi", 2, is(Items.CRAFTING_TABLE), PLANKS, PLANKS, PLANKS, PLANKS);
    private static final Recipe FURNACE_R = recipe("four", 3, is(Items.FURNACE),
            COBBLE, COBBLE, COBBLE,
            COBBLE, null, COBBLE,
            COBBLE, COBBLE, COBBLE);
    private static final Recipe BUCKET_R = recipe("seau", 3, is(Items.BUCKET),
            IRON, null, IRON,
            null, IRON, null,
            null, null, null);

    private static Recipe pickaxe(String name, Item result, Predicate<ItemStack> material) {
        return recipe(name, 3, is(result),
                material, material, material,
                null, STICK, null,
                null, STICK, null);
    }

    private static Recipe sword(String name, Item result, Predicate<ItemStack> material) {
        return recipe(name, 3, is(result),
                null, material, null,
                null, material, null,
                null, STICK, null);
    }

    private static final Recipe WOODEN_PICKAXE_R = pickaxe("pioche en bois", Items.WOODEN_PICKAXE, PLANKS);
    private static final Recipe STONE_PICKAXE_R = pickaxe("pioche en pierre", Items.STONE_PICKAXE, COBBLE);
    private static final Recipe IRON_PICKAXE_R = pickaxe("pioche en fer", Items.IRON_PICKAXE, IRON);
    private static final Recipe STONE_SWORD_R = sword("épée en pierre", Items.STONE_SWORD, COBBLE);
    private static final Recipe IRON_SWORD_R = sword("épée en fer", Items.IRON_SWORD, IRON);

    // --------------------------------------------------------------------------------------------------- state

    private enum Status {
        WORKING, DONE, FAILED
    }

    private static final PathingCommand DEFER = new PathingCommand(null, PathingCommandType.DEFER);
    private static final PathingCommand HOLD = new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);

    private boolean running;
    private int ticks;
    private String step = "";
    private boolean previousAllowInventory;

    // fighting
    private LivingEntity fightTarget;
    private boolean fightKeysDown;

    // eating
    private boolean eating;
    private int eatTicks;
    private int eatCooldownUntil;

    // crafting job
    private Recipe jobRecipe;
    private int jobTimes;
    private int jobPhase;
    private int jobTimer;
    private int openAttemptTick;
    private int lastContainerUse;

    // placing
    private int placeAttempts;
    private int placeCooldownUntil;

    // smelting
    private BlockPos furnacePos;
    private int smeltPending;
    private int smeltReadyTick;
    private int smeltJobPhase;
    private int smeltTimer;

    public BeatProcess(Baritone baritone) {
        super(baritone);
    }

    // -------------------------------------------------------------------------------------------- public API

    @Override
    public void start() {
        resetState();
        previousAllowInventory = Baritone.settings().allowInventory.value;
        // needed to pull tools and blocks from the main inventory to the hotbar
        Baritone.settings().allowInventory.value = true;
        running = true;
        logDirect("Beat: c'est parti. Étape 1 (âge du fer) : bois, établi, outils en bois / pierre / fer, four, seau. #beat stop ou #cancel pour arrêter.");
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
        resetState();
        baritone.getInputOverrideHandler().clearAllKeys();
        LocalPlayer p = ctx.player();
        if (p != null && p.containerMenu != p.inventoryMenu) {
            p.closeContainer();
        }
    }

    private void resetState() {
        ticks = 0;
        step = "";
        fightTarget = null;
        fightKeysDown = false;
        eating = false;
        eatTicks = 0;
        eatCooldownUntil = 0;
        jobRecipe = null;
        jobPhase = 0;
        jobTimer = 0;
        lastContainerUse = -100;
        placeAttempts = 0;
        placeCooldownUntil = 0;
        furnacePos = null;
        smeltPending = 0;
        smeltReadyTick = 0;
        smeltJobPhase = 0;
        smeltTimer = 0;
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

    private PathingCommand fail(String message) {
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
            logDirect("Beat: le joueur est mort, arrêt.");
            stopInternal();
            return DEFER;
        }
        ticks++;

        // 1. fight what is too close
        LivingEntity threat = pickThreat(p);
        if (threat != null) {
            return fight(p, threat);
        }
        if (fightTarget != null) {
            fightTarget = null;
            baritone.getInputOverrideHandler().clearAllKeys();
        }

        // 2. eat
        PathingCommand eat = tickEat(p);
        if (eat != null) {
            return eat;
        }

        // 3. don't leave a crafting table / furnace screen open for no reason
        if (p.containerMenu != p.inventoryMenu && ticks - lastContainerUse > 6) {
            p.closeContainer();
        }

        // 4. something else (mining, walking) is already doing its job
        if (delegating()) {
            return DEFER;
        }

        // 5. food
        PathingCommand hunt = tickHunt(p);
        if (hunt != null) {
            return hunt;
        }

        // 6. next milestone
        return nextStep(p);
    }

    private boolean delegating() {
        return baritone.getMineProcess().isActive()
                || baritone.getCustomGoalProcess().isActive()
                || baritone.getFollowProcess().isActive()
                || baritone.getExploreProcess().isActive();
    }

    // ---------------------------------------------------------------------------------------------- inventory

    private int count(Predicate<ItemStack> test) {
        int total = 0;
        for (ItemStack stack : ctx.player().getInventory().items) {
            if (!stack.isEmpty() && test.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private boolean has(Item item) {
        return count(is(item)) > 0;
    }

    // ----------------------------------------------------------------------------------------------- fighting

    private LivingEntity pickThreat(LocalPlayer p) {
        // keep going after what we are already fighting
        if (fightTarget != null && fightTarget.isAlive() && p.distanceToSqr(fightTarget) <= FIGHT_KEEP_SQ) {
            return fightTarget;
        }
        LivingEntity best = null;
        double bestDist = FIGHT_START_SQ;
        for (Entity entity : ctx.entities()) {
            if (!(entity instanceof Monster) || !entity.isAlive()) {
                continue;
            }
            // endermen, zombified piglins...: leave them alone unless they are angry
            if (entity instanceof NeutralMob && !((NeutralMob) entity).isAngry()) {
                continue;
            }
            double dist = p.distanceToSqr(entity);
            if (dist < bestDist) {
                bestDist = dist;
                best = (LivingEntity) entity;
            }
        }
        return best;
    }

    private PathingCommand fight(LocalPlayer p, LivingEntity target) {
        fightTarget = target;
        eating = false;
        jobRecipe = null;
        smeltJobPhase = 0;
        if (p.containerMenu != p.inventoryMenu) {
            p.closeContainer();
        }
        baritone.getInventoryBehavior().throwaway(true, s -> s.getItem() instanceof SwordItem, true);
        return attack(p, target);
    }

    /**
     * Face the target, walk up to it and hit it when the attack is charged
     */
    private PathingCommand attack(LocalPlayer p, LivingEntity target) {
        double dist = Math.sqrt(p.distanceToSqr(target));
        Rotation rotation = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), new Vec3(target.getX(), target.getY(0.5), target.getZ()), ctx.playerRotations());
        // aim directly so that walking forward goes towards the target
        p.setYRot(rotation.getYaw());
        p.setXRot(rotation.getPitch());
        if (dist <= ATTACK_REACH && p.getAttackStrengthScale(0.5F) >= 1.0F) {
            ctx.minecraft().gameMode.attack(p, target);
            p.swing(InteractionHand.MAIN_HAND);
        }
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, dist > 2.2);
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
        fightKeysDown = true;
        return HOLD;
    }

    // ------------------------------------------------------------------------------------------------ eating

    private PathingCommand tickEat(LocalPlayer p) {
        if (!eating) {
            if (p.getFoodData().getFoodLevel() > HUNGER_EAT_AT || ticks < eatCooldownUntil) {
                return null;
            }
            if (!baritone.getInventoryBehavior().throwaway(true, BeatProcess::isGoodFood, true)) {
                eatCooldownUntil = ticks + 200; // nothing to eat
                return null;
            }
            eating = true;
            eatTicks = 0;
        }
        eatTicks++;
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
        if ((eatTicks > 4 && !p.isUsingItem()) || eatTicks > 80) {
            eating = false;
            eatCooldownUntil = ticks + 20;
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
        }
        return HOLD;
    }

    /**
     * Hungry with nothing to eat: kill a cow / pig / sheep / chicken, then pick up what it drops
     */
    private PathingCommand tickHunt(LocalPlayer p) {
        if (p.getFoodData().getFoodLevel() > HUNGER_HUNT_AT || count(BeatProcess::isGoodFood) > 0) {
            return null;
        }
        // drops on the ground first
        ItemEntity drop = null;
        double dropDist = 12 * 12;
        LivingEntity animal = null;
        double animalDist = 24 * 24;
        for (Entity entity : ctx.entities()) {
            double dist = p.distanceToSqr(entity);
            if (entity instanceof ItemEntity && isGoodFood(((ItemEntity) entity).getItem()) && dist < dropDist) {
                drop = (ItemEntity) entity;
                dropDist = dist;
            } else if ((entity instanceof Cow || entity instanceof Pig || entity instanceof Sheep || entity instanceof Chicken)
                    && entity.isAlive() && !((LivingEntity) entity).isBaby() && dist < animalDist) {
                animal = (LivingEntity) entity;
                animalDist = dist;
            }
        }
        if (drop != null) {
            setStep("je ramasse de la nourriture");
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(drop.blockPosition(), 0));
            return DEFER;
        }
        if (animal != null) {
            setStep("j'ai faim : je chasse");
            baritone.getInventoryBehavior().throwaway(true, s -> s.getItem() instanceof SwordItem, true);
            if (animalDist <= ATTACK_REACH * ATTACK_REACH) {
                fightTarget = null;
                return attack(p, animal);
            }
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(animal.blockPosition(), 2));
            return DEFER;
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------- delegation

    private PathingCommand mineLogs(int wantedLogsInInventory) {
        setStep("je coupe du bois");
        baritone.getMineProcess().mine(wantedLogsInInventory, LOG_BLOCKS);
        return DEFER;
    }

    private PathingCommand mineBlocks(String what, int wantedInInventory, Block... blocks) {
        setStep(what);
        baritone.getMineProcess().mine(wantedInInventory, blocks);
        return DEFER;
    }

    private PathingCommand goNear(BlockPos pos) {
        baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(pos, 2));
        return DEFER;
    }

    // ------------------------------------------------------------------------------------------ milestones

    private PathingCommand nextStep(LocalPlayer p) {
        boolean woodPick = has(Items.WOODEN_PICKAXE) || has(Items.STONE_PICKAXE) || has(Items.IRON_PICKAXE);
        boolean stonePick = has(Items.STONE_PICKAXE) || has(Items.IRON_PICKAXE);
        boolean stoneSword = has(Items.STONE_SWORD) || has(Items.IRON_SWORD);
        boolean ironPick = has(Items.IRON_PICKAXE);
        boolean ironSword = has(Items.IRON_SWORD);
        boolean bucket = has(Items.BUCKET);

        if (ironPick && ironSword && bucket) {
            logDirect("Beat: étape 1 terminée (pioche et épée en fer, seau). Les étapes suivantes (Nether, forteresse, End, dragon) ne sont pas encore codées.");
            stopInternal();
            return DEFER;
        }

        int logs = count(LOG);
        int planks = count(PLANKS);
        int cobble = count(COBBLE);
        int woodBudget = logs * 4 + planks;

        // --- wood and the first pickaxe
        if (!woodPick) {
            if (woodBudget < 24) {
                return mineLogs(logs + (int) Math.ceil((24 - woodBudget) / 4.0));
            }
            setStep("je fabrique une pioche en bois");
            return craftAtTable(WOODEN_PICKAXE_R, PLANKS, 3, 2);
        }

        // --- stone tools
        if (!stonePick || !stoneSword) {
            int need = (stonePick ? 0 : 3) + (stoneSword ? 0 : 2);
            if (cobble < need) {
                return mineBlocks("je mine de la pierre", Math.max(16, need), Blocks.STONE);
            }
            if (woodBudget < 8) {
                return mineLogs(logs + 3);
            }
            if (!stonePick) {
                setStep("je fabrique une pioche en pierre");
                return craftAtTable(STONE_PICKAXE_R, COBBLE, 3, 2);
            }
            setStep("je fabrique une épée en pierre");
            return craftAtTable(STONE_SWORD_R, COBBLE, 2, 1);
        }

        // --- iron: mine, smelt, craft
        int ingots = count(IRON);
        int raw = count(RAW_IRON);
        int needIngots = (ironPick ? 0 : 3) + (ironSword ? 0 : 2) + (bucket ? 0 : 3);

        // collect what is cooking
        if (smeltPending > 0) {
            return collectSmelting(p);
        }

        if (ingots < needIngots) {
            if (ingots + raw < needIngots) {
                int missing = needIngots - ingots - raw;
                return mineBlocks("je cherche du fer", raw + missing, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE);
            }
            // enough raw iron: smelt it
            if (count(FUEL) < 8) {
                return mineLogs(logs + 6);
            }
            if (cobble < 8 && findBlockNear(p, Blocks.FURNACE, 6) == null && !has(Items.FURNACE)) {
                return mineBlocks("je mine de la pierre pour le four", 16, Blocks.STONE);
            }
            return startSmelting(p, raw);
        }

        // enough ingots: tools
        if (!ironPick) {
            setStep("je fabrique une pioche en fer");
            return craftAtTable(IRON_PICKAXE_R, IRON, 3, 2);
        }
        if (!ironSword) {
            setStep("je fabrique une épée en fer");
            return craftAtTable(IRON_SWORD_R, IRON, 2, 1);
        }
        setStep("je fabrique un seau");
        return craftAtTable(BUCKET_R, IRON, 3, 0);
    }

    /**
     * Crafts a 3x3 recipe at a crafting table, first making whatever it needs (sticks, planks, the table itself)
     *
     * @param material      what the recipe is made of
     * @param materialNeed  how many of them
     * @param sticksNeeded  how many sticks
     */
    private PathingCommand craftAtTable(Recipe recipe, Predicate<ItemStack> material, int materialNeed, int sticksNeeded) {
        LocalPlayer p = ctx.player();
        int planks = count(PLANKS);
        int logs = count(LOG);

        if (sticksNeeded > 0 && count(STICK) < sticksNeeded) {
            if (planks < 2) {
                return craftPlanks(1, logs);
            }
            return craftHold(STICKS_R, 1);
        }
        if (material == PLANKS && planks < materialNeed) {
            return craftPlanks((int) Math.ceil((materialNeed - planks) / 4.0), logs);
        }
        if (count(material) < materialNeed) {
            return fail("il me manque des matériaux pour " + recipe.name + ", je m'arrête.");
        }

        // a table within reach, otherwise place one
        if (findBlockNear(p, Blocks.CRAFTING_TABLE, 8) == null) {
            if (has(Items.CRAFTING_TABLE)) {
                return place(is(Items.CRAFTING_TABLE), Blocks.CRAFTING_TABLE);
            }
            if (planks < 4) {
                return craftPlanks(1, logs);
            }
            return craftHold(TABLE_R, 1);
        }
        BlockPos table = findBlockNear(p, Blocks.CRAFTING_TABLE, 8);
        if (!reachable(p, table)) {
            return goNear(table);
        }
        return craftHold(recipe, 1);
    }

    private PathingCommand craftPlanks(int times, int logs) {
        if (logs < times) {
            return mineLogs(logs + (times - logs) + 2);
        }
        return craftHold(PLANKS_R, times);
    }

    private PathingCommand craftHold(Recipe recipe, int times) {
        Status status = craft(recipe, times);
        if (status == Status.FAILED) {
            return fail("la fabrication de " + recipe.name + " a échoué, je m'arrête.");
        }
        return HOLD;
    }

    // --------------------------------------------------------------------------------------------- placing

    private boolean reachable(LocalPlayer p, BlockPos pos) {
        return p.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) <= CONTAINER_REACH;
    }

    private BlockPos findBlockNear(LocalPlayer p, Block block, int radius) {
        BlockPos feet = ctx.playerFeet();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = feet.offset(dx, dy, dz);
                    if (ctx.world().getBlockState(pos).is(block)) {
                        double dist = Vec3.atCenterOf(pos).distanceToSqr(p.position());
                        if (dist < bestDist) {
                            bestDist = dist;
                            best = pos;
                        }
                    }
                }
            }
        }
        return best;
    }

    /**
     * Places a block from the inventory on the ground next to us
     */
    private PathingCommand place(Predicate<ItemStack> item, Block expected) {
        LocalPlayer p = ctx.player();
        if (ticks < placeCooldownUntil) {
            return HOLD;
        }
        if (++placeAttempts > 25) {
            placeAttempts = 0;
            return fail("impossible de poser " + expected.getName().getString() + ", je m'arrête.");
        }
        if (!baritone.getInventoryBehavior().throwaway(true, item, true)) {
            return fail("je n'ai plus " + expected.getName().getString() + " à poser.");
        }
        BlockPos feet = ctx.playerFeet();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) {
                    continue; // that's us
                }
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos pos = feet.offset(dx, dy, dz);
                    BlockPos below = pos.below();
                    if (!ctx.world().getBlockState(pos).isAir() || !ctx.world().getBlockState(pos.above()).isAir()) {
                        continue;
                    }
                    if (!ctx.world().getBlockState(below).isFaceSturdy(ctx.world(), below, Direction.UP)) {
                        continue;
                    }
                    double dist = Vec3.atCenterOf(pos).distanceToSqr(p.position()) + Math.abs(dy) * 2;
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = pos;
                    }
                }
            }
        }
        if (best == null) {
            // no room here, walk a bit and try again
            placeCooldownUntil = ticks + 10;
            return HOLD;
        }
        BlockPos support = best.below();
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(support).add(0, 0.5, 0), Direction.UP, support, false);
        InteractionResult result = ctx.playerController().processRightClickBlock(p, ctx.world(), InteractionHand.MAIN_HAND, hit);
        if (result.consumesAction()) {
            p.swing(InteractionHand.MAIN_HAND);
            if (expected == Blocks.FURNACE) {
                furnacePos = best;
            }
            placeAttempts = 0;
        }
        placeCooldownUntil = ticks + 4;
        return HOLD;
    }

    // ---------------------------------------------------------------------------------------- containers

    /**
     * Right click a block to open its screen. Returns WORKING until the screen is there.
     */
    private Status openContainer(LocalPlayer p, Block block, Class<? extends AbstractContainerMenu> menuClass) {
        if (menuClass.isInstance(p.containerMenu)) {
            return Status.DONE;
        }
        if (p.containerMenu != p.inventoryMenu) {
            p.closeContainer(); // some other screen
            return Status.WORKING;
        }
        BlockPos pos = findBlockNear(p, block, 6);
        if (pos == null || !reachable(p, pos)) {
            return Status.FAILED;
        }
        if (ticks - openAttemptTick >= 10) {
            openAttemptTick = ticks;
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
            ctx.playerController().processRightClickBlock(p, ctx.world(), InteractionHand.MAIN_HAND, hit);
        }
        return Status.WORKING;
    }

    private void click(AbstractContainerMenu menu, int slot, int button, ClickType type) {
        ctx.playerController().windowClick(menu.containerId, slot, button, type, ctx.player());
    }

    /**
     * Finds a slot to take items from, among the player inventory slots of a menu
     */
    private int findSource(AbstractContainerMenu menu, int from, int to, Predicate<ItemStack> test, int minCount) {
        for (int i = from; i < to && i < menu.slots.size(); i++) {
            ItemStack stack = menu.slots.get(i).getItem();
            if (!stack.isEmpty() && stack.getCount() >= minCount && test.test(stack)) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------------------------------- crafting

    /**
     * Crafts a recipe by moving items around in the crafting grid, like a player. Call every tick until it is not
     * {@link Status#WORKING}. 2x2 recipes use the inventory, 3x3 recipes need a crafting table within reach.
     */
    private Status craft(Recipe recipe, int times) {
        LocalPlayer p = ctx.player();
        lastContainerUse = ticks;
        if (jobRecipe != recipe || jobTimes != times) {
            jobRecipe = recipe;
            jobTimes = times;
            jobPhase = 0;
            jobTimer = 0;
        }

        AbstractContainerMenu menu;
        int sourceFrom;
        int sourceTo;
        if (recipe.size == 2) {
            if (p.containerMenu != p.inventoryMenu) {
                p.closeContainer();
            }
            menu = p.inventoryMenu;
            sourceFrom = 9;
            sourceTo = 45;
        } else {
            Status opened = openContainer(p, Blocks.CRAFTING_TABLE, CraftingMenu.class);
            if (opened == Status.FAILED) {
                jobRecipe = null;
                return Status.FAILED;
            }
            if (opened == Status.WORKING) {
                if (++jobTimer > JOB_TIMEOUT) {
                    jobRecipe = null;
                    return Status.FAILED;
                }
                return Status.WORKING;
            }
            menu = p.containerMenu;
            sourceFrom = 10;
            sourceTo = 46;
        }
        int gridCells = recipe.size * recipe.size;

        switch (jobPhase) {
            case 0: { // fill the grid, all in this tick
                for (int cell = 0; cell < gridCells; cell++) {
                    Predicate<ItemStack> wanted = recipe.cells.get(cell);
                    if (wanted == null) {
                        continue;
                    }
                    int source = findSource(menu, sourceFrom, sourceTo, wanted, times);
                    if (source < 0) {
                        clearGrid(menu, gridCells);
                        jobRecipe = null;
                        return Status.FAILED;
                    }
                    int gridSlot = 1 + cell;
                    click(menu, source, 0, ClickType.PICKUP); // pick the stack up
                    for (int i = 0; i < times; i++) {
                        click(menu, gridSlot, 1, ClickType.PICKUP); // right click: drop one
                    }
                    click(menu, source, 0, ClickType.PICKUP); // put the rest back
                }
                jobPhase = 1;
                jobTimer = 0;
                return Status.WORKING;
            }
            case 1: { // wait for the server to tell us the result
                ItemStack result = menu.slots.get(0).getItem();
                if (!result.isEmpty() && recipe.result.test(result)) {
                    click(menu, 0, 0, ClickType.QUICK_MOVE);
                    jobPhase = 2;
                    jobTimer = 0;
                    return Status.WORKING;
                }
                if (++jobTimer > JOB_TIMEOUT) {
                    clearGrid(menu, gridCells);
                    jobRecipe = null;
                    return Status.FAILED;
                }
                return Status.WORKING;
            }
            default: { // let the inventory update, then make sure nothing is left in the grid
                if (++jobTimer < 3) {
                    return Status.WORKING;
                }
                clearGrid(menu, gridCells);
                jobRecipe = null;
                return Status.DONE;
            }
        }
    }

    private void clearGrid(AbstractContainerMenu menu, int gridCells) {
        for (int slot = 1; slot <= gridCells; slot++) {
            if (!menu.slots.get(slot).getItem().isEmpty()) {
                click(menu, slot, 0, ClickType.QUICK_MOVE);
            }
        }
    }

    // ------------------------------------------------------------------------------------------- smelting

    /**
     * Loads the furnace with all the raw iron and some fuel, then lets it cook
     */
    private PathingCommand startSmelting(LocalPlayer p, int rawIron) {
        setStep("je fais fondre le fer");
        lastContainerUse = ticks;
        if (findBlockNear(p, Blocks.FURNACE, 8) == null) {
            if (has(Items.FURNACE)) {
                return place(is(Items.FURNACE), Blocks.FURNACE);
            }
            if (count(COBBLE) < 8) {
                return mineBlocks("je mine de la pierre pour le four", 16, Blocks.STONE);
            }
            if (findBlockNear(p, Blocks.CRAFTING_TABLE, 8) == null) {
                // a furnace needs a table: reuse the generic path
                return craftAtTable(FURNACE_R, COBBLE, 8, 0);
            }
            return craftAtTable(FURNACE_R, COBBLE, 8, 0);
        }
        BlockPos furnace = findBlockNear(p, Blocks.FURNACE, 8);
        furnacePos = furnace;
        if (!reachable(p, furnace)) {
            return goNear(furnace);
        }
        Status opened = openContainer(p, Blocks.FURNACE, FurnaceMenu.class);
        if (opened == Status.FAILED) {
            return fail("je n'arrive pas à ouvrir le four, je m'arrête.");
        }
        if (opened == Status.WORKING) {
            if (++smeltTimer > JOB_TIMEOUT) {
                smeltTimer = 0;
                return fail("le four ne s'ouvre pas, je m'arrête.");
            }
            return HOLD;
        }
        smeltTimer = 0;
        AbstractContainerMenu menu = p.containerMenu;

        int inputSource = findSource(menu, 3, 39, RAW_IRON, 1);
        if (inputSource < 0) {
            return fail("plus de fer brut à fondre, je m'arrête.");
        }
        // fuel: coal first, otherwise wood
        Predicate<ItemStack> fuelType = count(COAL) > 0 ? COAL : (count(LOG) > 0 ? LOG : PLANKS);
        int fuelSource = findSource(menu, 3, 39, fuelType, 1);
        if (fuelSource < 0) {
            return fail("plus de combustible pour le four, je m'arrête.");
        }
        int perItem = fuelType == COAL ? 8 : 1; // a log smelts 1.5 items, so be generous
        int fuelUnits = Math.max(1, (int) Math.ceil(rawIron / (double) perItem) + (fuelType == COAL ? 0 : rawIron / 2 + 1));
        fuelUnits = Math.min(fuelUnits, menu.slots.get(fuelSource).getItem().getCount());

        click(menu, inputSource, 0, ClickType.PICKUP);
        click(menu, 0, 0, ClickType.PICKUP); // whole stack into the input slot
        click(menu, inputSource, 0, ClickType.PICKUP);

        fuelSource = findSource(menu, 3, 39, fuelType, 1);
        if (fuelSource >= 0) {
            click(menu, fuelSource, 0, ClickType.PICKUP);
            for (int i = 0; i < fuelUnits; i++) {
                click(menu, 1, 1, ClickType.PICKUP);
            }
            click(menu, fuelSource, 0, ClickType.PICKUP);
        }

        smeltPending = rawIron;
        smeltReadyTick = ticks + rawIron * TICKS_PER_SMELT + 60;
        smeltJobPhase = 0;
        logDirect("Beat: " + rawIron + " fer(s) en cours de fonte, environ " + (rawIron * 10) + " secondes.");
        p.closeContainer();
        return HOLD;
    }

    /**
     * Waits for the furnace (cutting wood meanwhile), then takes the iron ingots out
     */
    private PathingCommand collectSmelting(LocalPlayer p) {
        if (ticks < smeltReadyTick) {
            // make use of the wait
            int logs = count(LOG);
            if (logs < 12) {
                return mineLogs(logs + 6);
            }
            setStep("j'attends que le fer fonde");
            return HOLD;
        }
        setStep("je récupère le fer fondu");
        lastContainerUse = ticks;
        BlockPos furnace = findBlockNear(p, Blocks.FURNACE, 8);
        if (furnace == null) {
            if (furnacePos != null) {
                return goNear(furnacePos);
            }
            return fail("je ne retrouve plus le four, je m'arrête.");
        }
        furnacePos = furnace;
        if (!reachable(p, furnace)) {
            return goNear(furnace);
        }
        Status opened = openContainer(p, Blocks.FURNACE, FurnaceMenu.class);
        if (opened == Status.FAILED) {
            return fail("je n'arrive pas à rouvrir le four, je m'arrête.");
        }
        if (opened == Status.WORKING) {
            if (++smeltTimer > JOB_TIMEOUT) {
                smeltTimer = 0;
                return fail("le four ne s'ouvre pas, je m'arrête.");
            }
            return HOLD;
        }
        smeltTimer = 0;
        AbstractContainerMenu menu = p.containerMenu;
        if (!menu.slots.get(2).getItem().isEmpty()) {
            click(menu, 2, 0, ClickType.QUICK_MOVE);
            return HOLD;
        }
        if (!menu.slots.get(0).getItem().isEmpty()) {
            // still cooking: wait a little more
            smeltReadyTick = ticks + 100;
            p.closeContainer();
            return HOLD;
        }
        smeltPending = 0;
        p.closeContainer();
        return HOLD;
    }
}
