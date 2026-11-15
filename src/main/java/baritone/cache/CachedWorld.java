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

package baritone.cache;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.cache.ICachedWorld;
import baritone.api.cache.IWorldData;
import baritone.api.utils.Helper;
import com.google.common.cache.CacheBuilder;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.DimensionType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author Brady
 * @since 8/4/2018
 */
public final class CachedWorld implements ICachedWorld, Helper {

    /**
     * The maximum number of regions in any direction from (0,0)
     */
    private static final int REGION_MAX = 30_000_000 / 512 + 1;

    /**
     * A map of all of the cached regions.
     */
    private Long2ObjectMap<CachedRegion> cachedRegions = new Long2ObjectOpenHashMap<>();

    /**
     * The directory that the cached region files are saved to
     */
    private final String directory;

    /**
     * Queue of positions to pack. Refers to the toPackMap, in that every element of this queue will be a
     * key in that map.
     */
    private final LinkedBlockingQueue<ChunkPos> toPackQueue = new LinkedBlockingQueue<>();

    /**
     * All chunk positions pending packing. This map will be updated in-place if a new update to the chunk occurs
     * while waiting in the queue for the packer thread to get to it.
     */
    private final Map<ChunkPos, LevelChunk> toPackMap = CacheBuilder.newBuilder().softValues().<ChunkPos, LevelChunk>build().asMap();

    private final DimensionType dimension;

    CachedWorld(Path directory, DimensionType dimension) {
        if (!Files.exists(directory)) {
            try {
                Files.createDirectories(directory);
            } catch (IOException ignored) {
            }
        }
        this.directory = directory.toString();
        this.dimension = dimension;
        System.out.println("Cached world directory: " + directory);
        Baritone.getExecutor().execute(new PackerThread());
        Baritone.getExecutor().execute(() -> {
            try {
                Thread.sleep(30000);
                while (true) {
                    // since a region only saves if it's been modified since its last save
                    // saving every 10 minutes means that once it's time to exit
                    // we'll only have a couple regions to save
                    save();
                    Thread.sleep(600000);
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
    }

    @Override
    public final void queueForPacking(LevelChunk chunk) {
        if (toPackMap.put(chunk.getPos(), chunk) == null) {
            toPackQueue.add(chunk.getPos());
        }
    }

    @Override
    public final boolean isCached(int blockX, int blockZ) {
        CachedRegion region = getRegion(blockX >> 9, blockZ >> 9);
        if (region == null) {
            return false;
        }
        return region.isCached(blockX & 511, blockZ & 511);
    }

    public final boolean regionLoaded(int blockX, int blockZ) {
        return getRegion(blockX >> 9, blockZ >> 9) != null;
    }

    @Override
    public final ArrayList<BlockPos> getLocationsOf(String block, int maximum, int centerX, int centerZ, int maxRegionDistanceSq) {
        ArrayList<BlockPos> res = new ArrayList<>();
        int centerRegionX = centerX >> 9;
        int centerRegionZ = centerZ >> 9;

        int searchRadius = 0;
        while (searchRadius <= maxRegionDistanceSq) {
            for (int xoff = -searchRadius; xoff <= searchRadius; xoff++) {
                for (int zoff = -searchRadius; zoff <= searchRadius; zoff++) {
                    int distance = xoff * xoff + zoff * zoff;
                    if (distance != searchRadius) {
                        continue;
                    }
                    int regionX = xoff + centerRegionX;
                    int regionZ = zoff + centerRegionZ;
                    CachedRegion region = getOrCreateRegion(regionX, regionZ);
                    if (region != null) {
                        // TODO: 100% verify if this or addAll is faster.
                        res.addAll(region.getLocationsOf(block));
                    }
                }
            }
            if (res.size() >= maximum) {
                return res;
            }
            searchRadius++;
        }
        return res;
    }

    /**
     * Visits every remembered special block (see {@code CachedChunk.BLOCKS_TO_KEEP_TRACK_OF} and the
     * {@code extraCachedBlocks} setting) within {@code radius} blocks of a point, however far away it is.
     * <p>
     * Unlike {@link #getLocationsOf}, this never keeps a region in RAM that wasn't already there: regions on disk are
     * answered from their small index file. A region without an up to date index is parsed once to build it, which is
     * slow, so call this from a background thread.
     *
     * @param nameFilter which block names (as stored in the cache) to report
     * @param out        called once per matching block, from the calling thread
     */
    public void scanKnown(int centerX, int centerZ, int radius, Predicate<String> nameFilter, BiConsumer<String, BlockPos> out) {
        final long radiusSq = (long) radius * radius;
        final Path dir = Paths.get(this.directory);
        for (int regionX = (centerX - radius) >> 9; regionX <= (centerX + radius) >> 9; regionX++) {
            for (int regionZ = (centerZ - radius) >> 9; regionZ <= (centerZ + radius) >> 9; regionZ++) {
                if (regionDistanceSq(regionX, regionZ, centerX, centerZ) > radiusSq) {
                    continue;
                }
                Map<String, List<BlockPos>> blocks;
                CachedRegion inRam = getRegion(regionX, regionZ);
                if (inRam != null) {
                    blocks = inRam.collectSpecialBlocks();
                } else if (Files.exists(CachedRegion.getRegionFile(dir, regionX, regionZ))) {
                    blocks = RegionIndex.readOrBuild(dir, regionX, regionZ, dimension);
                } else {
                    continue;
                }
                for (Map.Entry<String, List<BlockPos>> entry : blocks.entrySet()) {
                    if (!nameFilter.test(entry.getKey())) {
                        continue;
                    }
                    for (BlockPos pos : entry.getValue()) {
                        long dx = pos.getX() - centerX;
                        long dz = pos.getZ() - centerZ;
                        if (dx * dx + dz * dz <= radiusSq) {
                            out.accept(entry.getKey(), pos);
                        }
                    }
                }
            }
        }
    }

    /**
     * @return how many regions within the radius are only on disk and have no up to date index, i.e. how many
     * regions {@link #scanKnown} would have to parse (slowly) first
     */
    public int regionsNeedingIndex(int centerX, int centerZ, int radius) {
        final long radiusSq = (long) radius * radius;
        final Path dir = Paths.get(this.directory);
        int count = 0;
        for (int regionX = (centerX - radius) >> 9; regionX <= (centerX + radius) >> 9; regionX++) {
            for (int regionZ = (centerZ - radius) >> 9; regionZ <= (centerZ + radius) >> 9; regionZ++) {
                if (regionDistanceSq(regionX, regionZ, centerX, centerZ) > radiusSq
                        || getRegion(regionX, regionZ) != null
                        || !Files.exists(CachedRegion.getRegionFile(dir, regionX, regionZ))) {
                    continue;
                }
                if (!RegionIndex.isFresh(dir, regionX, regionZ)) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Builds the index of every region file on disk that doesn't have an up to date one, one region at a time (so only
     * one region is ever parsed in RAM). Slow, call from a background thread.
     *
     * @return how many indexes were built
     */
    public int reindexAll() {
        final Path dir = Paths.get(this.directory);
        List<String> files;
        try (Stream<Path> listing = Files.list(dir)) {
            files = listing.map(p -> p.getFileName().toString())
                    .filter(name -> name.startsWith("r.") && name.endsWith(".bcr"))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            e.printStackTrace();
            return 0;
        }
        int built = 0;
        for (String file : files) {
            String[] parts = file.split("\\.");
            if (parts.length != 4) {
                continue;
            }
            int regionX;
            int regionZ;
            try {
                regionX = Integer.parseInt(parts[1]);
                regionZ = Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                continue;
            }
            if (getRegion(regionX, regionZ) != null || RegionIndex.isFresh(dir, regionX, regionZ)) {
                continue; // in RAM: its index gets written on its next save
            }
            RegionIndex.build(dir, regionX, regionZ, dimension);
            built++;
        }
        return built;
    }

    /**
     * Squared distance in blocks from a point to the closest point of a region
     */
    private static long regionDistanceSq(int regionX, int regionZ, int x, int z) {
        long minX = (long) regionX << 9;
        long minZ = (long) regionZ << 9;
        long dx = Math.max(0L, Math.max(minX - x, x - (minX + 511)));
        long dz = Math.max(0L, Math.max(minZ - z, z - (minZ + 511)));
        return dx * dx + dz * dz;
    }

    private void updateCachedChunk(CachedChunk chunk) {
        CachedRegion region = getOrCreateRegion(chunk.x >> 5, chunk.z >> 5);
        region.updateCachedChunk(chunk.x & 31, chunk.z & 31, chunk);
    }

    @Override
    public final void save() {
        if (!Baritone.settings().chunkCaching.value) {
            System.out.println("Not saving to disk; chunk caching is disabled.");
            allRegions().forEach(region -> {
                if (region != null) {
                    region.removeExpired();
                }
            }); // even if we aren't saving to disk, still delete expired old chunks from RAM
            prune();
            return;
        }
        long start = System.nanoTime() / 1000000L;
        allRegions().parallelStream().forEach(region -> {
            if (region != null) {
                region.save(this.directory);
            }
        });
        long now = System.nanoTime() / 1000000L;
        System.out.println("World save took " + (now - start) + "ms");
        prune();
    }

    /**
     * Delete regions that are too far from the player
     */
    private synchronized void prune() {
        if (!Baritone.settings().pruneRegionsFromRAM.value) {
            return;
        }
        BlockPos pruneCenter = guessPosition();
        for (CachedRegion region : allRegions()) {
            if (region == null) {
                continue;
            }
            int distX = ((region.getX() << 9) + 256) - pruneCenter.getX();
            int distZ = ((region.getZ() << 9) + 256) - pruneCenter.getZ();
            double dist = Math.sqrt(distX * distX + distZ * distZ);
            if (dist > 1024) {
                logDebug("Deleting cached region from ram");
                cachedRegions.remove(getRegionID(region.getX(), region.getZ()));
            }
        }
    }

    /**
     * If we are still in this world and dimension, return player feet, otherwise return most recently modified chunk
     */
    private BlockPos guessPosition() {
        for (IBaritone ibaritone : BaritoneAPI.getProvider().getAllBaritones()) {
            IWorldData data = ibaritone.getWorldProvider().getCurrentWorld();
            if (data != null && data.getCachedWorld() == this && ibaritone.getPlayerContext().player() != null) {
                return ibaritone.getPlayerContext().playerFeet();
            }
        }
        CachedChunk mostRecentlyModified = null;
        for (CachedRegion region : allRegions()) {
            if (region == null) {
                continue;
            }
            CachedChunk ch = region.mostRecentlyModified();
            if (ch == null) {
                continue;
            }
            if (mostRecentlyModified == null || mostRecentlyModified.cacheTimestamp < ch.cacheTimestamp) {
                mostRecentlyModified = ch;
            }
        }
        if (mostRecentlyModified == null) {
            return new BlockPos(0, 0, 0);
        }
        return new BlockPos((mostRecentlyModified.x << 4) + 8, 0, (mostRecentlyModified.z << 4) + 8);
    }

    private synchronized List<CachedRegion> allRegions() {
        return new ArrayList<>(this.cachedRegions.values());
    }

    @Override
    public final void reloadAllFromDisk() {
        long start = System.nanoTime() / 1000000L;
        allRegions().forEach(region -> {
            if (region != null) {
                region.load(this.directory);
            }
        });
        long now = System.nanoTime() / 1000000L;
        System.out.println("World load took " + (now - start) + "ms");
    }

    @Override
    public final synchronized CachedRegion getRegion(int regionX, int regionZ) {
        return cachedRegions.get(getRegionID(regionX, regionZ));
    }

    /**
     * Returns the region at the specified region coordinates. If a
     * region is not found, then a new one is created.
     *
     * @param regionX The region X coordinate
     * @param regionZ The region Z coordinate
     * @return The region located at the specified coordinates
     */
    private synchronized CachedRegion getOrCreateRegion(int regionX, int regionZ) {
        return cachedRegions.computeIfAbsent(getRegionID(regionX, regionZ), id -> {
            CachedRegion newRegion = new CachedRegion(regionX, regionZ, dimension);
            newRegion.load(this.directory);
            return newRegion;
        });
    }

    public void tryLoadFromDisk(int regionX, int regionZ) {
        getOrCreateRegion(regionX, regionZ);
    }

    /**
     * Returns the region ID based on the region coordinates. 0 will be
     * returned if the specified region coordinates are out of bounds.
     *
     * @param regionX The region X coordinate
     * @param regionZ The region Z coordinate
     * @return The region ID
     */
    private long getRegionID(int regionX, int regionZ) {
        if (!isRegionInWorld(regionX, regionZ)) {
            return 0;
        }

        return (long) regionX & 0xFFFFFFFFL | ((long) regionZ & 0xFFFFFFFFL) << 32;
    }

    /**
     * Returns whether or not the specified region coordinates is within the world bounds.
     *
     * @param regionX The region X coordinate
     * @param regionZ The region Z coordinate
     * @return Whether or not the region is in world bounds
     */
    private boolean isRegionInWorld(int regionX, int regionZ) {
        return regionX <= REGION_MAX && regionX >= -REGION_MAX && regionZ <= REGION_MAX && regionZ >= -REGION_MAX;
    }

    private class PackerThread implements Runnable {

        public void run() {
            while (true) {
                try {
                    ChunkPos pos = toPackQueue.take();
                    LevelChunk chunk = toPackMap.remove(pos);
                    if (toPackQueue.size() > Baritone.settings().chunkPackerQueueMaxSize.value) {
                        continue;
                    }
                    CachedChunk cached = ChunkPacker.pack(chunk);
                    CachedWorld.this.updateCachedChunk(cached);
                    //System.out.println("Processed chunk at " + chunk.x + "," + chunk.z);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    break;
                } catch (Throwable th) {
                    // in the case of an exception, keep consuming from the queue so as not to leak memory
                    th.printStackTrace();
                }
            }
        }
    }
}
