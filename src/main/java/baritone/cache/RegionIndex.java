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

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.dimension.DimensionType;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * A tiny sidecar file ({@code r.X.Z.bsi}) saved next to each cached region file ({@code r.X.Z.bcr}) that only lists the
 * positions of the "special" blocks of that region (chests, spawners, ores, bells...).
 * <p>
 * A region file holds the whole terrain (up to ~16 MiB once in RAM), but the index is a few KiB. That lets us answer
 * "what do we know about within 5000 blocks" by reading small files, without ever keeping a region in memory.
 * <p>
 * The index remembers the last-modified time of the region file it was built from, so it is ignored (and rebuilt) as
 * soon as the region file changes behind its back.
 */
final class RegionIndex {

    /**
     * Magic value, "BSI1"
     */
    private static final int MAGIC = 0x42534931;

    /**
     * Sanity limit so that a corrupted file can't make us allocate gigabytes
     */
    private static final int MAX_ENTRIES = 5_000_000;

    private RegionIndex() {}

    static Path indexFile(Path dir, int regionX, int regionZ) {
        return dir.resolve("r." + regionX + "." + regionZ + ".bsi");
    }

    private static long stampOf(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * Writes the index of a region. Call this right after the region file itself has been written and closed.
     *
     * @param blocks block name to absolute positions
     */
    static void write(Path dir, int regionX, int regionZ, Map<String, List<BlockPos>> blocks) throws IOException {
        long stamp = stampOf(CachedRegion.getRegionFile(dir, regionX, regionZ));
        if (stamp < 0) {
            return; // no region file, nothing to be consistent with
        }
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(indexFile(dir, regionX, regionZ).toFile()), 8192)))) {
            out.writeInt(MAGIC);
            out.writeLong(stamp);
            out.writeInt(blocks.size());
            for (Map.Entry<String, List<BlockPos>> entry : blocks.entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeInt(entry.getValue().size());
                for (BlockPos pos : entry.getValue()) {
                    out.writeInt(pos.getX());
                    out.writeInt(pos.getY());
                    out.writeInt(pos.getZ());
                }
            }
        }
    }

    /**
     * Cheap check (reads only the header) of whether an up to date index exists
     */
    static boolean isFresh(Path dir, int regionX, int regionZ) {
        Path idx = indexFile(dir, regionX, regionZ);
        if (!Files.exists(idx)) {
            return false;
        }
        long stamp = stampOf(CachedRegion.getRegionFile(dir, regionX, regionZ));
        try (DataInputStream in = open(idx)) {
            return in.readInt() == MAGIC && in.readLong() == stamp;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * @return the index contents, or {@code null} if it is missing, unreadable or out of date
     */
    static Map<String, List<BlockPos>> readIfFresh(Path dir, int regionX, int regionZ) {
        Path idx = indexFile(dir, regionX, regionZ);
        if (!Files.exists(idx)) {
            return null;
        }
        long stamp = stampOf(CachedRegion.getRegionFile(dir, regionX, regionZ));
        try (DataInputStream in = open(idx)) {
            if (in.readInt() != MAGIC || in.readLong() != stamp) {
                return null;
            }
            int names = in.readInt();
            if (names < 0 || names > MAX_ENTRIES) {
                return null;
            }
            Map<String, List<BlockPos>> res = new HashMap<>();
            for (int i = 0; i < names; i++) {
                String name = in.readUTF();
                int count = in.readInt();
                if (count < 0 || count > MAX_ENTRIES) {
                    return null;
                }
                List<BlockPos> positions = new ArrayList<>(count);
                for (int j = 0; j < count; j++) {
                    int x = in.readInt();
                    int y = in.readInt();
                    int z = in.readInt();
                    positions.add(new BlockPos(x, y, z));
                }
                res.put(name, positions);
            }
            return res;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Parses the region file into a throwaway {@link CachedRegion} (so nothing is kept in RAM afterwards), writes its
     * index, and returns the contents. Slow: this is the full cost of loading a region from disk.
     */
    static Map<String, List<BlockPos>> build(Path dir, int regionX, int regionZ, DimensionType dimension) {
        CachedRegion temporary = new CachedRegion(regionX, regionZ, dimension);
        temporary.load(dir.toString());
        Map<String, List<BlockPos>> blocks = temporary.collectSpecialBlocks();
        try {
            write(dir, regionX, regionZ, blocks);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return blocks;
    }

    static Map<String, List<BlockPos>> readOrBuild(Path dir, int regionX, int regionZ, DimensionType dimension) {
        Map<String, List<BlockPos>> res = readIfFresh(dir, regionX, regionZ);
        return res != null ? res : build(dir, regionX, regionZ, dimension);
    }

    private static DataInputStream open(Path file) throws IOException {
        return new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(file.toFile()), 8192)));
    }
}
