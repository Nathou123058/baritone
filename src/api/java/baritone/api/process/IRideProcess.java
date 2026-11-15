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

package baritone.api.process;

import baritone.api.pathing.goals.Goal;
import net.minecraft.core.BlockPos;

/**
 * Drives a vehicle or mount (horse, donkey, mule, camel, pig, boat...) that the player is
 * <b>already riding</b> towards a position.
 * <p>
 * This is deliberately not a full pathfinder: it steers in a straight line towards the target
 * and does simple look-ahead obstacle avoidance.
 */
public interface IRideProcess extends IBaritoneProcess {

    /**
     * Start riding towards the position of the given goal. Only goals that have a position are
     * supported ({@link baritone.api.pathing.goals.GoalBlock}, {@link baritone.api.pathing.goals.GoalXZ},
     * {@link baritone.api.pathing.goals.GoalNear}, {@link baritone.api.pathing.goals.GoalGetToBlock}).
     *
     * @param goal The goal
     * @throws IllegalArgumentException if the goal has no usable position
     * @throws IllegalStateException    if the player is not riding something that can be steered
     */
    void rideTo(Goal goal);

    /**
     * Start riding towards the given X/Z column.
     *
     * @param x X coordinate
     * @param z Z coordinate
     * @throws IllegalStateException if the player is not riding something that can be steered
     */
    void rideTo(int x, int z);

    /**
     * @return The block position currently being ridden to, or {@code null} if not active.
     * Y is meaningless when the target was given as X/Z only.
     */
    BlockPos currentDestination();

    /**
     * Stop riding. Does not dismount the player.
     */
    void stop();
}
