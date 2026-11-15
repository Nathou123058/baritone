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

package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.datatypes.BlockById;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.BlockUtils;
import baritone.cache.CachedChunk;
import baritone.cache.CachedWorld;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static baritone.api.command.IBaritoneChatControl.FORCE_COMMAND_PREFIX;

/**
 * Looks things up in the world cache from any distance, using only the small per-region index files, so it doesn't
 * load the (heavy) regions into RAM. All the work runs on a background thread.
 */
public class MapCommand extends Command {

    private static final int DEFAULT_RADIUS = 5000;
    private static final int MAX_RADIUS = 30000;
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;

    private final AtomicBoolean busy = new AtomicBoolean(false);

    public MapCommand(IBaritone baritone) {
        super(baritone, "map");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        if (!(ctx.worldData().getCachedWorld() instanceof CachedWorld)) {
            throw new CommandInvalidStateException("No world cache available");
        }
        final CachedWorld world = (CachedWorld) ctx.worldData().getCachedWorld();
        final BetterBlockPos origin = ctx.playerFeet();

        if (args.hasAny() && args.peekString().equals("reindex")) {
            args.getString();
            args.requireMax(0);
            startTask(() -> {
                logDirect("Indexing every cached region on disk. This is slow (once per region) but doesn't keep them in RAM.");
                int built = world.reindexAll();
                logDirect("Done: built " + built + " region index(es).");
            });
            return;
        }

        if (!args.hasAny() || args.peekString().equals("summary")) {
            if (args.hasAny()) {
                args.getString();
            }
            final int radius = args.hasAny() ? clamp(args.getAs(Integer.class), 1, MAX_RADIUS) : DEFAULT_RADIUS;
            args.requireMax(0);
            startTask(() -> summary(world, origin, radius));
            return;
        }

        final Block block = args.getDatatypeFor(BlockById.INSTANCE);
        final int radius = args.hasAny() ? clamp(args.getAs(Integer.class), 1, MAX_RADIUS) : DEFAULT_RADIUS;
        final int limit = args.hasAny() ? clamp(args.getAs(Integer.class), 1, MAX_LIMIT) : DEFAULT_LIMIT;
        args.requireMax(0);
        if (!CachedChunk.BLOCKS_TO_KEEP_TRACK_OF.contains(block) && !Baritone.settings().extraCachedBlocks.value.contains(block)) {
            throw new CommandInvalidStateException(BlockUtils.blockToString(block) + " isn't remembered by the cache. Add it to the extraCachedBlocks setting, then explore the area again.");
        }
        startTask(() -> locate(world, origin, block, radius, limit));
    }

    /**
     * Runs a scan on a background thread, one at a time
     */
    private void startTask(Runnable task) throws CommandInvalidStateException {
        if (!busy.compareAndSet(false, true)) {
            throw new CommandInvalidStateException("A map scan is already running, wait for it to finish.");
        }
        Baritone.getExecutor().execute(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                t.printStackTrace();
                logDirect("Map scan failed: " + t);
            } finally {
                busy.set(false);
            }
        });
    }

    private void summary(CachedWorld world, BetterBlockPos origin, int radius) {
        int toIndex = world.regionsNeedingIndex(origin.x, origin.z, radius);
        if (toIndex > 0) {
            logDirect("Indexing " + toIndex + " region(s) from disk first. This is slow, but only happens once per region.");
        }
        final Map<String, Stat> stats = new TreeMap<>();
        world.scanKnown(origin.x, origin.z, radius, name -> true, (name, pos) -> {
            Stat stat = stats.computeIfAbsent(name, k -> new Stat());
            stat.count++;
            long distSq = distSq(origin, pos);
            if (stat.nearest == null || distSq < stat.nearestDistSq) {
                stat.nearest = pos;
                stat.nearestDistSq = distSq;
            }
        });
        if (stats.isEmpty()) {
            logDirect("Nothing remembered within " + radius + " blocks. Explore more, or check the extraCachedBlocks setting.");
            return;
        }
        List<Map.Entry<String, Stat>> sorted = new ArrayList<>(stats.entrySet());
        sorted.sort(Comparator.comparingLong(e -> e.getValue().nearestDistSq));
        logDirect("Remembered within " + radius + " blocks (nearest first):");
        for (Map.Entry<String, Stat> entry : sorted) {
            Stat stat = entry.getValue();
            logDirect(describe(entry.getKey() + " x" + stat.count + ", nearest", origin, stat.nearest));
        }
    }

    private void locate(CachedWorld world, BetterBlockPos origin, Block block, int radius, int limit) {
        int toIndex = world.regionsNeedingIndex(origin.x, origin.z, radius);
        if (toIndex > 0) {
            logDirect("Indexing " + toIndex + " region(s) from disk first. This is slow, but only happens once per region.");
        }
        final String name = BlockUtils.blockToString(block);
        // keeps only the `limit` closest: the head of the queue is the farthest of them
        final PriorityQueue<BlockPos> closest = new PriorityQueue<>(Comparator.comparingLong((BlockPos p) -> distSq(origin, p)).reversed());
        final int[] total = {0};
        world.scanKnown(origin.x, origin.z, radius, name::equals, (n, pos) -> {
            total[0]++;
            closest.add(pos);
            if (closest.size() > limit) {
                closest.poll();
            }
        });
        if (total[0] == 0) {
            logDirect("No " + name + " remembered within " + radius + " blocks.");
            return;
        }
        List<BlockPos> sorted = new ArrayList<>(closest);
        sorted.sort(Comparator.comparingLong(p -> distSq(origin, p)));
        logDirect(total[0] + " " + name + " remembered within " + radius + " blocks, showing the " + sorted.size() + " closest:");
        for (BlockPos pos : sorted) {
            logDirect(describe(name, origin, pos));
        }
    }

    private static long distSq(BlockPos origin, BlockPos pos) {
        long dx = pos.getX() - origin.getX();
        long dz = pos.getZ() - origin.getZ();
        return dx * dx + dz * dz;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String compass(double dx, double dz) {
        // +x is east and +z is south
        double angle = (Math.toDegrees(Math.atan2(dz, dx)) + 360.0) % 360.0;
        String[] names = {"E", "SE", "S", "SW", "W", "NW", "N", "NE"};
        return names[(int) Math.round(angle / 45.0) % 8];
    }

    /**
     * A chat line that sets the goal to the position when clicked
     */
    private Component describe(String label, BetterBlockPos origin, BlockPos pos) {
        double dx = pos.getX() - origin.x;
        double dz = pos.getZ() - origin.z;
        String where = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        String text = String.format("%s: %s (%d blocks %s)", label, where, Math.round(Math.sqrt(dx * dx + dz * dz)), compass(dx, dz));
        MutableComponent component = Component.literal(text);
        component.setStyle(component.getStyle()
                .withColor(ChatFormatting.GRAY)
                .withInsertion(where)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, FORCE_COMMAND_PREFIX + "goal " + where))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to set goal to this position"))));
        return component;
    }

    private static final class Stat {
        int count;
        BlockPos nearest;
        long nearestDistSq;
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            return new TabCompleteHelper()
                    .append("summary", "reindex")
                    .append(Stream.concat(CachedChunk.BLOCKS_TO_KEEP_TRACK_OF.stream(), Baritone.settings().extraCachedBlocks.value.stream())
                            .map(b -> BuiltInRegistries.BLOCK.getKey(b).getPath()))
                    .filterPrefix(args.getString())
                    .sortAlphabetically()
                    .stream();
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Find what the cache remembers, from any distance";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Looks up the blocks Baritone remembers (chests, spawners, beds, and by default diamonds, ancient debris, emeralds and village bells)",
                "as far away as you want. It reads small per-region index files instead of loading regions, so it doesn't cost RAM, and runs in the background.",
                "The first time a region is queried it has to be indexed from disk, which is slow; after that it is instant.",
                "",
                "Only what you have already seen is known. Which blocks are remembered is set by the extraCachedBlocks setting, and only applies to chunks cached after changing it.",
                "",
                "Usage:",
                "> map - Summary of everything remembered within 5000 blocks",
                "> map summary [radius] - Same, with a custom radius",
                "> map <block> [radius] [limit] - The closest remembered <block>, e.g. map diamond_ore 8000 10",
                "> map reindex - Build the index of every cached region now"
        );
    }
}
