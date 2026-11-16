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
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.Helper;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.tags.BlockTags;
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
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The survival skills shared by {@link BeatProcess} and {@link BuildSupplyProcess}: stay alive (fight, eat, hunt, wear
 * armor) and get any item, the way a player would.
 * <p>
 * {@link #acquire(Item, int)} is the main entry point. It works out how to get an item from the game's own recipes:
 * mine it, kill something for it, smelt it, or craft it from ingredients that are themselves acquired the same way
 * (so a diamond pickaxe asks for diamonds, which asks for an iron pickaxe, which asks for iron...).
 * <p>
 * It is driven one tick at a time and keeps almost no state: every tick it looks at the inventory again.
 */
public final class Survival implements Helper {

    // ------------------------------------------------------------------------------------------------ tuning

    private static final double FIGHT_START_SQ = 4.5 * 4.5;
    private static final double FIGHT_KEEP_SQ = 8.0 * 8.0;
    private static final double ATTACK_REACH = 3.0;
    private static final double CONTAINER_REACH = 4.4;
    private static final int HUNGER_EAT_AT = 14;
    private static final int HUNGER_HUNT_AT = 12;
    private static final int JOB_TIMEOUT = 60;
    private static final int TICKS_PER_SMELT = 200;
    private static final int MAX_CRAFTS_AT_ONCE = 16;

    // -------------------------------------------------------------------------------------------- knowledge

    private static final Block[] LOG_BLOCKS = {
            Blocks.OAK_LOG, Blocks.SPRUCE_LOG, Blocks.BIRCH_LOG, Blocks.JUNGLE_LOG,
            Blocks.ACACIA_LOG, Blocks.DARK_OAK_LOG, Blocks.MANGROVE_LOG, Blocks.CHERRY_LOG
    };

    /**
     * Items that come out of the ground when a block is mined (the blocks that drop them)
     */
    private static final Map<Item, Block[]> RESOURCES = new HashMap<>();

    /**
     * Items that come from killing something
     */
    private static final Map<Item, Class<?>[]> MOBS = new HashMap<>();

    private static void resource(Item item, Block... blocks) {
        RESOURCES.put(item, blocks);
    }

    static {
        for (Block log : LOG_BLOCKS) {
            resource(log.asItem(), log);
        }
        resource(Items.COBBLESTONE, Blocks.STONE);
        resource(Items.COBBLED_DEEPSLATE, Blocks.DEEPSLATE);
        resource(Items.DIRT, Blocks.DIRT, Blocks.GRASS_BLOCK);
        resource(Items.SAND, Blocks.SAND);
        resource(Items.RED_SAND, Blocks.RED_SAND);
        resource(Items.GRAVEL, Blocks.GRAVEL);
        resource(Items.FLINT, Blocks.GRAVEL);
        resource(Items.CLAY_BALL, Blocks.CLAY);
        resource(Items.COAL, Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE);
        resource(Items.RAW_IRON, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE);
        resource(Items.RAW_COPPER, Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE);
        resource(Items.RAW_GOLD, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE);
        resource(Items.REDSTONE, Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE);
        resource(Items.LAPIS_LAZULI, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE);
        resource(Items.DIAMOND, Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE);
        resource(Items.EMERALD, Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE);
        resource(Items.OBSIDIAN, Blocks.OBSIDIAN);
        resource(Items.NETHERRACK, Blocks.NETHERRACK);
        resource(Items.QUARTZ, Blocks.NETHER_QUARTZ_ORE);
        resource(Items.SOUL_SAND, Blocks.SOUL_SAND);
        resource(Items.BLACKSTONE, Blocks.BLACKSTONE);
        resource(Items.SUGAR_CANE, Blocks.SUGAR_CANE);
        resource(Items.ANDESITE, Blocks.ANDESITE);
        resource(Items.DIORITE, Blocks.DIORITE);
        resource(Items.GRANITE, Blocks.GRANITE);

        MOBS.put(Items.BEEF, new Class<?>[]{Cow.class});
        MOBS.put(Items.LEATHER, new Class<?>[]{Cow.class});
        MOBS.put(Items.PORKCHOP, new Class<?>[]{Pig.class});
        MOBS.put(Items.MUTTON, new Class<?>[]{Sheep.class});
        MOBS.put(Items.WHITE_WOOL, new Class<?>[]{Sheep.class});
        MOBS.put(Items.CHICKEN, new Class<?>[]{Chicken.class});
        MOBS.put(Items.FEATHER, new Class<?>[]{Chicken.class});
    }

    private static final Set<Item> BAD_FOOD = new HashSet<>(Arrays.asList(
            Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO, Items.PUFFERFISH, Items.CHORUS_FRUIT,
            Items.SUSPICIOUS_STEW, Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE
    ));

    private static boolean isGoodFood(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem().isEdible() && !BAD_FOOD.contains(stack.getItem());
    }

    private static boolean isLog(Item item) {
        return new ItemStack(item).is(ItemTags.LOGS);
    }

    private static final Predicate<ItemStack> LOG = s -> s.is(ItemTags.LOGS);
    private static final Predicate<ItemStack> PLANKS = s -> s.is(ItemTags.PLANKS);
    private static final Predicate<ItemStack> COAL = s -> s.is(Items.COAL) || s.is(Items.CHARCOAL);
    private static final Predicate<ItemStack> FUEL = s -> LOG.test(s) || PLANKS.test(s) || COAL.test(s);

    private static String name(Item item) {
        return item.getDescription().getString();
    }

    // --------------------------------------------------------------------------------------------------- state

    private enum Status {
        WORKING, DONE, FAILED
    }

    private static final PathingCommand DEFER = new PathingCommand(null, PathingCommandType.DEFER);
    private static final PathingCommand HOLD = new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);

    private final Baritone baritone;
    private final IPlayerContext ctx;

    private int ticks;
    private String failure;
    private String step = "";

    private LivingEntity fightTarget;

    private boolean eating;
    private int eatTicks;
    private int eatCooldownUntil;
    private int armorCooldownUntil;

    // crafting job
    private CraftingRecipe jobRecipe;
    private int jobTimes;
    private int jobPhase;
    private int jobTimer;
    private int openAttemptTick;
    private int lastContainerUse;

    // placing
    private int placeAttempts;
    private int placeCooldownUntil;

    // smelting
    private int smeltPending;
    private int smeltReadyTick;
    private int smeltTimer;
    private BlockPos furnacePos;

    // caches
    private RecipeManager cachedManager;
    private Map<Item, List<CraftingRecipe>> craftingByResult;
    private Map<Item, List<SmeltingRecipe>> smeltingByResult;
    private final Map<Item, Integer> estimates = new HashMap<>();
    private Item nearestLog;
    private int nearestLogTick = -1000;

    public Survival(Baritone baritone) {
        this.baritone = baritone;
        this.ctx = baritone.getPlayerContext();
        reset();
    }

    public void reset() {
        ticks = 0;
        failure = null;
        step = "";
        fightTarget = null;
        eating = false;
        eatTicks = 0;
        eatCooldownUntil = 0;
        armorCooldownUntil = 0;
        jobRecipe = null;
        jobPhase = 0;
        jobTimer = 0;
        openAttemptTick = -100;
        lastContainerUse = -100;
        placeAttempts = 0;
        placeCooldownUntil = 0;
        smeltPending = 0;
        smeltReadyTick = 0;
        smeltTimer = 0;
        furnacePos = null;
        nearestLogTick = -1000;
    }

    /**
     * Call once per game tick before anything else
     */
    public void beginTick() {
        ticks++;
        estimates.clear();
    }

    /**
     * @return why the last {@link #acquire} gave up, or {@code null}
     */
    public String failure() {
        return failure;
    }

    public void clearFailure() {
        failure = null;
    }

    public String step() {
        return step;
    }

    private void setStep(String newStep) {
        if (!newStep.equals(step)) {
            step = newStep;
            logDirect("Beat: " + newStep);
        }
    }

    private PathingCommand fail(String message) {
        failure = message;
        return HOLD;
    }

    /**
     * Stop whatever we were holding down and close any screen
     */
    public void shutdown() {
        baritone.getInputOverrideHandler().clearAllKeys();
        LocalPlayer p = ctx.player();
        if (p != null && p.containerMenu != p.inventoryMenu) {
            p.closeContainer();
        }
        fightTarget = null;
        eating = false;
        jobRecipe = null;
        smeltPending = 0;
    }

    public boolean delegating() {
        return baritone.getMineProcess().isActive()
                || baritone.getCustomGoalProcess().isActive()
                || baritone.getFollowProcess().isActive()
                || baritone.getExploreProcess().isActive();
    }

    // ---------------------------------------------------------------------------------------------- inventory

    public int countItem(Item item) {
        int total = 0;
        for (ItemStack stack : ctx.player().getInventory().items) {
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private int count(Predicate<ItemStack> test) {
        int total = 0;
        for (ItemStack stack : ctx.player().getInventory().items) {
            if (!stack.isEmpty() && test.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private int bestPickaxeLevel() {
        int best = -1;
        for (ItemStack stack : ctx.player().getInventory().items) {
            if (stack.getItem() instanceof PickaxeItem) {
                best = Math.max(best, ((PickaxeItem) stack.getItem()).getTier().getLevel());
            }
        }
        return best;
    }

    // ------------------------------------------------------------------------------------------------ basics

    /**
     * Stay alive: fight what is too close, eat, wear armor, pick up drops, hunt when starving.
     *
     * @return a command if it took over this tick, otherwise {@code null}
     */
    public PathingCommand basics() {
        LocalPlayer p = ctx.player();

        LivingEntity threat = pickThreat(p);
        if (threat != null) {
            fightTarget = threat;
            eating = false;
            jobRecipe = null;
            if (p.containerMenu != p.inventoryMenu) {
                p.closeContainer();
            }
            baritone.getInventoryBehavior().throwaway(true, s -> s.getItem() instanceof SwordItem, true);
            return attack(p, threat);
        }
        if (fightTarget != null) {
            fightTarget = null;
            baritone.getInputOverrideHandler().clearAllKeys();
        }

        PathingCommand eat = tickEat(p);
        if (eat != null) {
            return eat;
        }

        // don't leave a crafting table / furnace screen open for no reason
        if (p.containerMenu != p.inventoryMenu && ticks - lastContainerUse > 6) {
            p.closeContainer();
        }

        wearArmor(p);
        return null;
    }

    private LivingEntity pickThreat(LocalPlayer p) {
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
        return HOLD;
    }

    private PathingCommand tickEat(LocalPlayer p) {
        if (!eating) {
            if (p.getFoodData().getFoodLevel() > HUNGER_EAT_AT || ticks < eatCooldownUntil) {
                return null;
            }
            if (!baritone.getInventoryBehavior().throwaway(true, Survival::isGoodFood, true)) {
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
     * Puts on armor pieces (and a shield) that are in the inventory, by shift clicking them
     */
    private void wearArmor(LocalPlayer p) {
        if (ticks < armorCooldownUntil || p.containerMenu != p.inventoryMenu) {
            return;
        }
        armorCooldownUntil = ticks + 20;
        List<ItemStack> items = p.getInventory().items;
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            boolean wear = false;
            if (stack.getItem() instanceof ArmorItem) {
                wear = p.getItemBySlot(((ArmorItem) stack.getItem()).getEquipmentSlot()).isEmpty();
            } else if (stack.getItem() instanceof ShieldItem) {
                wear = p.getOffhandItem().isEmpty();
            }
            if (wear) {
                // hotbar slots are 36-44 in the inventory menu, the main inventory is 9-35
                click(p.inventoryMenu, i < 9 ? 36 + i : i, 0, ClickType.QUICK_MOVE);
                return;
            }
        }
    }

    /**
     * Walks to food and mob drops lying around, and hunts when starving.
     *
     * @return a command if it took over this tick, otherwise {@code null}
     */
    public PathingCommand foraging() {
        LocalPlayer p = ctx.player();
        ItemEntity drop = null;
        double dropDist = 8 * 8;
        for (Entity entity : ctx.entities()) {
            if (entity instanceof ItemEntity && entity.isAlive()) {
                ItemStack stack = ((ItemEntity) entity).getItem();
                if ((isGoodFood(stack) || MOBS.containsKey(stack.getItem())) && p.distanceToSqr(entity) < dropDist) {
                    drop = (ItemEntity) entity;
                    dropDist = p.distanceToSqr(entity);
                }
            }
        }
        if (drop != null) {
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(drop.blockPosition(), 0));
            return DEFER;
        }
        if (p.getFoodData().getFoodLevel() <= HUNGER_HUNT_AT && count(Survival::isGoodFood) == 0) {
            return huntFor(Items.BEEF, 1, new Class<?>[]{Cow.class, Pig.class, Sheep.class, Chicken.class}, "j'ai faim : je chasse");
        }
        return null;
    }

    // ----------------------------------------------------------------------------------------------- acquire

    /**
     * Gets {@code count} of an item into the inventory, however it has to be done.
     *
     * @return {@code null} when the inventory already has enough. Otherwise a command for this tick, or the sign that
     * it is delegating. If it can't be done at all, {@link #failure()} says why.
     */
    public PathingCommand acquire(Item item, int count) {
        failure = null;
        return acquire(item, count, new HashSet<>(), 0);
    }

    private PathingCommand acquire(Item item, int count, Set<Item> path, int depth) {
        if (countItem(item) >= count) {
            return null;
        }
        if (depth > 14 || !path.add(item)) {
            return fail("je tourne en rond pour obtenir " + name(item));
        }
        ensureRecipes();

        Block[] blocks = RESOURCES.get(item);
        if (blocks != null) {
            return gather(item, blocks, count, path, depth);
        }
        Class<?>[] mobs = MOBS.get(item);
        if (mobs != null) {
            return huntFor(item, count, mobs, "je chasse pour " + name(item));
        }
        List<SmeltingRecipe> smelting = smeltingByResult.get(item);
        if (smelting != null && !smelting.isEmpty()) {
            return smelt(item, bestSmelting(smelting), count, path, depth);
        }
        return craftItem(item, count, path, depth);
    }

    // ---- mining

    private int requiredPickaxeLevel(Block block) {
        BlockState state = block.defaultBlockState();
        if (state.is(BlockTags.NEEDS_DIAMOND_TOOL)) {
            return 3;
        }
        if (state.is(BlockTags.NEEDS_IRON_TOOL)) {
            return 2;
        }
        if (state.is(BlockTags.NEEDS_STONE_TOOL)) {
            return 1;
        }
        return state.requiresCorrectToolForDrops() ? 0 : -1;
    }

    private Item pickaxeForLevel(int level) {
        switch (level) {
            case 0:
                return Items.WOODEN_PICKAXE;
            case 1:
                return Items.STONE_PICKAXE;
            case 2:
                return Items.IRON_PICKAXE;
            default:
                return Items.DIAMOND_PICKAXE;
        }
    }

    private PathingCommand gather(Item item, Block[] blocks, int count, Set<Item> path, int depth) {
        int need = requiredPickaxeLevel(blocks[0]);
        if (need >= 0 && bestPickaxeLevel() < need) {
            return acquire(pickaxeForLevel(need), 1, path, depth + 1); // first the tool
        }
        setStep("je récupère " + name(item));
        baritone.getMineProcess().mine(count, blocks);
        return DEFER;
    }

    // ---- killing

    private PathingCommand huntFor(Item item, int count, Class<?>[] kinds, String label) {
        LocalPlayer p = ctx.player();
        LivingEntity target = null;
        double best = 48 * 48;
        for (Entity entity : ctx.entities()) {
            if (!(entity instanceof LivingEntity) || !entity.isAlive() || ((LivingEntity) entity).isBaby()) {
                continue;
            }
            for (Class<?> kind : kinds) {
                if (kind.isInstance(entity) && p.distanceToSqr(entity) < best) {
                    best = p.distanceToSqr(entity);
                    target = (LivingEntity) entity;
                }
            }
        }
        setStep(label);
        if (target == null) {
            // nothing around: look elsewhere
            if (!baritone.getExploreProcess().isActive()) {
                baritone.getExploreProcess().explore((int) p.getX(), (int) p.getZ());
            }
            return DEFER;
        }
        if (baritone.getExploreProcess().isActive()) {
            baritone.getExploreProcess().onLostControl();
        }
        baritone.getInventoryBehavior().throwaway(true, s -> s.getItem() instanceof SwordItem, true);
        if (best <= ATTACK_REACH * ATTACK_REACH) {
            return attack(p, target);
        }
        baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(target.blockPosition(), 2));
        return DEFER;
    }

    // ---- recipes

    private void ensureRecipes() {
        RecipeManager manager = ctx.world().getRecipeManager();
        if (manager == cachedManager && craftingByResult != null) {
            return;
        }
        cachedManager = manager;
        craftingByResult = new HashMap<>();
        smeltingByResult = new HashMap<>();
        RegistryAccess access = ctx.world().registryAccess();
        for (CraftingRecipe recipe : manager.getAllRecipesFor(RecipeType.CRAFTING)) {
            if (recipe.isSpecial()) {
                continue;
            }
            craftingByResult.computeIfAbsent(recipe.getResultItem(access).getItem(), k -> new ArrayList<>()).add(recipe);
        }
        for (SmeltingRecipe recipe : manager.getAllRecipesFor(RecipeType.SMELTING)) {
            smeltingByResult.computeIfAbsent(recipe.getResultItem(access).getItem(), k -> new ArrayList<>()).add(recipe);
        }
    }

    private int countMatching(Ingredient ingredient) {
        int total = 0;
        for (ItemStack stack : ctx.player().getInventory().items) {
            if (!stack.isEmpty() && ingredient.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private Item nearestLogItem() {
        if (ticks - nearestLogTick < 100) {
            return nearestLog;
        }
        nearestLogTick = ticks;
        nearestLog = Items.OAK_LOG;
        BlockPos feet = ctx.playerFeet();
        double best = Double.MAX_VALUE;
        for (int dx = -16; dx <= 16; dx++) {
            for (int dz = -16; dz <= 16; dz++) {
                for (int dy = -6; dy <= 12; dy++) {
                    BlockState state = ctx.world().getBlockState(feet.offset(dx, dy, dz));
                    if (state.is(BlockTags.LOGS) && !state.isAir()) {
                        double dist = dx * dx + dy * dy + dz * dz;
                        if (dist < best) {
                            best = dist;
                            nearestLog = state.getBlock().asItem();
                        }
                    }
                }
            }
        }
        return nearestLog;
    }

    /**
     * A rough idea of how hard an item is to get, to choose between alternatives. 0 means we already have some.
     */
    private int estimate(Item item, int depth) {
        Integer cached = estimates.get(item);
        if (cached != null) {
            return cached;
        }
        estimates.put(item, 40); // guards against cycles
        int cost;
        if (countItem(item) > 0) {
            cost = 0;
        } else if (RESOURCES.containsKey(item)) {
            cost = isLog(item) ? (item == nearestLogItem() ? 1 : 8) : 2;
        } else if (MOBS.containsKey(item)) {
            cost = 6;
        } else if (depth <= 0) {
            cost = 40;
        } else {
            cost = 40;
            List<SmeltingRecipe> smelting = smeltingByResult.get(item);
            if (smelting != null && !smelting.isEmpty()) {
                cost = 3 + ingredientCost(bestSmelting(smelting).getIngredients().get(0), depth - 1);
            } else {
                List<CraftingRecipe> recipes = craftingByResult.get(item);
                if (recipes != null) {
                    for (CraftingRecipe recipe : recipes) {
                        if (isCyclic(recipe, item)) {
                            continue;
                        }
                        int total = 1;
                        for (Ingredient ingredient : recipe.getIngredients()) {
                            if (!ingredient.isEmpty()) {
                                total += ingredientCost(ingredient, depth - 1);
                            }
                        }
                        cost = Math.min(cost, total);
                    }
                }
            }
        }
        estimates.put(item, cost);
        return cost;
    }

    private SmeltingRecipe bestSmelting(List<SmeltingRecipe> recipes) {
        SmeltingRecipe best = recipes.get(0);
        int bestCost = Integer.MAX_VALUE;
        for (SmeltingRecipe recipe : recipes) {
            int cost = ingredientCost(recipe.getIngredients().get(0), 3);
            if (cost < bestCost) {
                bestCost = cost;
                best = recipe;
            }
        }
        return best;
    }

    private int ingredientCost(Ingredient ingredient, int depth) {
        int best = 40;
        for (ItemStack stack : ingredient.getItems()) {
            best = Math.min(best, estimate(stack.getItem(), depth));
        }
        return best;
    }

    private boolean isCyclic(CraftingRecipe recipe, Item result) {
        ItemStack probe = new ItemStack(result);
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (!ingredient.isEmpty() && ingredient.test(probe)) {
                return true;
            }
        }
        return false;
    }

    private Item bestItemFor(Ingredient ingredient, Set<Item> path) {
        Item best = null;
        int bestCost = Integer.MAX_VALUE;
        for (ItemStack stack : ingredient.getItems()) {
            Item candidate = stack.getItem();
            if (path.contains(candidate)) {
                continue;
            }
            int cost = estimate(candidate, 4);
            if (cost < bestCost) {
                bestCost = cost;
                best = candidate;
            }
        }
        return best;
    }

    // ---- crafting

    private PathingCommand craftItem(Item item, int count, Set<Item> path, int depth) {
        List<CraftingRecipe> recipes = craftingByResult.get(item);
        CraftingRecipe best = null;
        int bestCost = Integer.MAX_VALUE;
        if (recipes != null) {
            for (CraftingRecipe recipe : recipes) {
                if (isCyclic(recipe, item)) {
                    continue;
                }
                int total = 1;
                for (Ingredient ingredient : recipe.getIngredients()) {
                    if (!ingredient.isEmpty()) {
                        total += ingredientCost(ingredient, 3);
                    }
                }
                if (total < bestCost) {
                    bestCost = total;
                    best = recipe;
                }
            }
        }
        if (best == null || bestCost >= 40) {
            return fail("je ne sais pas comment obtenir " + name(item));
        }

        int perCraft = Math.max(1, best.getResultItem(ctx.world().registryAccess()).getCount());
        int crafts = (int) Math.ceil((count - countItem(item)) / (double) perCraft);
        int times = Math.max(1, Math.min(crafts, MAX_CRAFTS_AT_ONCE));

        // is everything there? otherwise go and get the first thing that is missing
        Map<String, Integer> demand = new HashMap<>();
        Map<String, Ingredient> byKey = new HashMap<>();
        for (Ingredient ingredient : best.getIngredients()) {
            if (ingredient.isEmpty()) {
                continue;
            }
            String key = ingredientKey(ingredient);
            demand.merge(key, times, Integer::sum);
            byKey.put(key, ingredient);
        }
        for (Map.Entry<String, Integer> entry : demand.entrySet()) {
            Ingredient ingredient = byKey.get(entry.getKey());
            int matching = countMatching(ingredient);
            if (matching < entry.getValue()) {
                Item source = bestItemFor(ingredient, path);
                if (source == null) {
                    return fail("il me manque un ingrédient pour " + name(item));
                }
                int missing = entry.getValue() - matching;
                PathingCommand sub = acquire(source, countItem(source) + missing, path, depth + 1);
                // if the ingredient is satisfied by other items than the one we chose, there is nothing to do but look again
                return sub != null ? sub : HOLD;
            }
        }

        setStep("je fabrique " + name(item));
        if (!fits2x2(best)) {
            BlockPos table = findBlockNear(ctx.player(), Blocks.CRAFTING_TABLE, 8);
            if (table == null) {
                if (countItem(Items.CRAFTING_TABLE) > 0) {
                    return place(Items.CRAFTING_TABLE, Blocks.CRAFTING_TABLE);
                }
                return acquire(Items.CRAFTING_TABLE, 1, path, depth + 1);
            }
            if (!reachable(ctx.player(), table)) {
                return goNear(table);
            }
        }
        Status status = craft(best, times);
        if (status == Status.FAILED) {
            return fail("la fabrication de " + name(item) + " a échoué");
        }
        return HOLD;
    }

    private static String ingredientKey(Ingredient ingredient) {
        StringBuilder key = new StringBuilder();
        for (ItemStack stack : ingredient.getItems()) {
            key.append(stack.getItem().hashCode()).append(',');
        }
        return key.toString();
    }

    private static boolean fits2x2(CraftingRecipe recipe) {
        if (recipe instanceof ShapedRecipe) {
            return ((ShapedRecipe) recipe).getWidth() <= 2 && ((ShapedRecipe) recipe).getHeight() <= 2;
        }
        int used = 0;
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (!ingredient.isEmpty()) {
                used++;
            }
        }
        return used <= 4;
    }

    /**
     * Where each ingredient goes in a crafting grid of the given size (row major), {@link Ingredient#EMPTY} if nothing
     */
    private static List<Ingredient> layout(CraftingRecipe recipe, int size) {
        List<Ingredient> cells = new ArrayList<>(Collections.nCopies(size * size, Ingredient.EMPTY));
        List<Ingredient> ingredients = recipe.getIngredients();
        if (recipe instanceof ShapedRecipe) {
            int width = ((ShapedRecipe) recipe).getWidth();
            int height = ((ShapedRecipe) recipe).getHeight();
            for (int row = 0; row < height; row++) {
                for (int col = 0; col < width; col++) {
                    cells.set(row * size + col, ingredients.get(row * width + col));
                }
            }
        } else {
            int cell = 0;
            for (Ingredient ingredient : ingredients) {
                if (!ingredient.isEmpty()) {
                    cells.set(cell++, ingredient);
                }
            }
        }
        return cells;
    }

    /**
     * Crafts a recipe by moving items around in the crafting grid, like a player. Call every tick until it is not
     * {@link Status#WORKING}. 2x2 recipes use the inventory, bigger ones need a crafting table within reach.
     */
    private Status craft(CraftingRecipe recipe, int times) {
        LocalPlayer p = ctx.player();
        lastContainerUse = ticks;
        if (jobRecipe != recipe || jobTimes != times) {
            jobRecipe = recipe;
            jobTimes = times;
            jobPhase = 0;
            jobTimer = 0;
        }

        boolean small = fits2x2(recipe);
        int size = small ? 2 : 3;
        AbstractContainerMenu menu;
        int sourceFrom;
        int sourceTo;
        if (small) {
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
        int gridCells = size * size;
        Item resultItem = recipe.getResultItem(ctx.world().registryAccess()).getItem();

        switch (jobPhase) {
            case 0: { // fill the grid, all in this tick
                List<Ingredient> cells = layout(recipe, size);
                int amount = times;
                if (!canFill(menu, cells, sourceFrom, sourceTo, amount)) {
                    amount = 1; // the ingredients are spread over several stacks
                    if (!canFill(menu, cells, sourceFrom, sourceTo, amount)) {
                        jobRecipe = null;
                        return Status.FAILED;
                    }
                }
                for (int cell = 0; cell < gridCells; cell++) {
                    Ingredient wanted = cells.get(cell);
                    if (wanted.isEmpty()) {
                        continue;
                    }
                    int source = findSource(menu, sourceFrom, sourceTo, wanted, amount);
                    if (source < 0) {
                        clearGrid(menu, gridCells);
                        jobRecipe = null;
                        return Status.FAILED;
                    }
                    click(menu, source, 0, ClickType.PICKUP); // pick the stack up
                    for (int i = 0; i < amount; i++) {
                        click(menu, 1 + cell, 1, ClickType.PICKUP); // right click: drop one
                    }
                    click(menu, source, 0, ClickType.PICKUP); // put the rest back
                }
                jobPhase = 1;
                jobTimer = 0;
                return Status.WORKING;
            }
            case 1: { // wait for the server to tell us the result
                ItemStack result = menu.slots.get(0).getItem();
                if (!result.isEmpty() && result.is(resultItem)) {
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

    private boolean canFill(AbstractContainerMenu menu, List<Ingredient> cells, int from, int to, int amount) {
        for (Ingredient ingredient : cells) {
            if (!ingredient.isEmpty() && findSource(menu, from, to, ingredient, amount) < 0) {
                return false;
            }
        }
        return true;
    }

    private void clearGrid(AbstractContainerMenu menu, int gridCells) {
        for (int slot = 1; slot <= gridCells; slot++) {
            if (!menu.slots.get(slot).getItem().isEmpty()) {
                click(menu, slot, 0, ClickType.QUICK_MOVE);
            }
        }
    }

    // ---- smelting

    private PathingCommand smelt(Item item, SmeltingRecipe recipe, int count, Set<Item> path, int depth) {
        LocalPlayer p = ctx.player();
        if (smeltPending > 0) {
            return collectSmelting(p);
        }
        Ingredient ingredient = recipe.getIngredients().get(0);
        int toSmelt = count - countItem(item);
        int matching = countMatching(ingredient);
        if (matching < toSmelt) {
            Item source = bestItemFor(ingredient, path);
            if (source == null) {
                return fail("il me manque de quoi fabriquer " + name(item));
            }
            return acquire(source, countItem(source) + (toSmelt - matching), path, depth + 1);
        }

        // fuel
        int fuelNeeded = (int) Math.ceil(toSmelt / 1.5) + 1;
        int fuelHave = count(FUEL);
        if (fuelHave < fuelNeeded) {
            Item log = countItem(Items.COAL) > 0 ? Items.COAL : nearestLogItem();
            for (ItemStack stack : p.getInventory().items) {
                if (LOG.test(stack)) {
                    log = stack.getItem();
                    break;
                }
            }
            return acquire(log, countItem(log) + (fuelNeeded - fuelHave), path, depth + 1);
        }

        // a furnace
        BlockPos furnace = findBlockNear(p, Blocks.FURNACE, 8);
        if (furnace == null) {
            if (countItem(Items.FURNACE) > 0) {
                return place(Items.FURNACE, Blocks.FURNACE);
            }
            return acquire(Items.FURNACE, 1, path, depth + 1);
        }
        furnacePos = furnace;
        if (!reachable(p, furnace)) {
            return goNear(furnace);
        }

        setStep("je fais fondre pour obtenir " + name(item));
        lastContainerUse = ticks;
        Status opened = openContainer(p, Blocks.FURNACE, FurnaceMenu.class);
        if (opened == Status.FAILED) {
            return fail("je n'arrive pas à ouvrir le four");
        }
        if (opened == Status.WORKING) {
            if (++smeltTimer > JOB_TIMEOUT) {
                smeltTimer = 0;
                return fail("le four ne s'ouvre pas");
            }
            return HOLD;
        }
        smeltTimer = 0;
        AbstractContainerMenu menu = p.containerMenu;

        int inputSource = findSource(menu, 3, 39, ingredient, 1);
        if (inputSource < 0) {
            return fail("plus rien à faire fondre");
        }
        Predicate<ItemStack> fuelType = count(COAL) > 0 ? COAL : (count(LOG) > 0 ? LOG : PLANKS);
        boolean coal = fuelType == COAL;
        int fuelSource = findSource(menu, 3, 39, fuelType);
        if (fuelSource < 0) {
            return fail("plus de combustible pour le four");
        }
        int fuelUnits = coal ? (int) Math.ceil(toSmelt / 8.0) : (int) Math.ceil(toSmelt / 1.5) + 1;
        fuelUnits = Math.max(1, Math.min(fuelUnits, menu.slots.get(fuelSource).getItem().getCount()));

        click(menu, inputSource, 0, ClickType.PICKUP);
        click(menu, 0, 0, ClickType.PICKUP); // the whole stack into the input slot
        click(menu, inputSource, 0, ClickType.PICKUP);

        fuelSource = findSource(menu, 3, 39, fuelType);
        if (fuelSource >= 0) {
            click(menu, fuelSource, 0, ClickType.PICKUP);
            for (int i = 0; i < fuelUnits; i++) {
                click(menu, 1, 1, ClickType.PICKUP);
            }
            click(menu, fuelSource, 0, ClickType.PICKUP);
        }

        smeltPending = toSmelt;
        smeltReadyTick = ticks + toSmelt * TICKS_PER_SMELT + 60;
        logDirect("Beat: " + toSmelt + " à faire fondre, environ " + (toSmelt * 10) + " secondes.");
        p.closeContainer();
        return HOLD;
    }

    /**
     * Waits for the furnace (cutting wood meanwhile), then takes what came out
     */
    private PathingCommand collectSmelting(LocalPlayer p) {
        if (ticks < smeltReadyTick) {
            int logs = count(LOG);
            if (logs < 12) {
                Item log = nearestLogItem();
                setStep("j'attends la fonte en coupant du bois");
                baritone.getMineProcess().mine(countItem(log) + 6, Block.byItem(log));
                return DEFER;
            }
            setStep("j'attends la fonte");
            return HOLD;
        }
        setStep("je récupère ce qui a fondu");
        lastContainerUse = ticks;
        BlockPos furnace = findBlockNear(p, Blocks.FURNACE, 8);
        if (furnace == null) {
            if (furnacePos != null) {
                return goNear(furnacePos);
            }
            smeltPending = 0;
            return fail("je ne retrouve plus le four");
        }
        furnacePos = furnace;
        if (!reachable(p, furnace)) {
            return goNear(furnace);
        }
        Status opened = openContainer(p, Blocks.FURNACE, FurnaceMenu.class);
        if (opened == Status.FAILED) {
            smeltPending = 0;
            return fail("je n'arrive pas à rouvrir le four");
        }
        if (opened == Status.WORKING) {
            if (++smeltTimer > JOB_TIMEOUT) {
                smeltTimer = 0;
                smeltPending = 0;
                return fail("le four ne s'ouvre pas");
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
            smeltReadyTick = ticks + 100; // still cooking
            p.closeContainer();
            return HOLD;
        }
        smeltPending = 0;
        p.closeContainer();
        return HOLD;
    }

    // ---------------------------------------------------------------------------------------------- placing

    private boolean reachable(LocalPlayer p, BlockPos pos) {
        return p.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) <= CONTAINER_REACH;
    }

    private PathingCommand goNear(BlockPos pos) {
        baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(pos, 2));
        return DEFER;
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
    private PathingCommand place(Item item, Block expected) {
        LocalPlayer p = ctx.player();
        if (ticks < placeCooldownUntil) {
            return HOLD;
        }
        if (++placeAttempts > 25) {
            placeAttempts = 0;
            return fail("impossible de poser " + expected.getName().getString());
        }
        if (!baritone.getInventoryBehavior().throwaway(true, s -> s.is(item), true)) {
            return fail("je n'ai plus " + expected.getName().getString() + " à poser");
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
            placeCooldownUntil = ticks + 10; // no room here
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

    private int findSource(AbstractContainerMenu menu, int from, int to, Ingredient ingredient, int minCount) {
        return findSource(menu, from, to, ingredient::test, minCount);
    }

    private int findSource(AbstractContainerMenu menu, int from, int to, Predicate<ItemStack> test) {
        return findSource(menu, from, to, test, 1);
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
}
