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

/**
 * Plays survival Minecraft on its own, from nothing, towards beating the game. No cheats: it only mines, crafts,
 * smelts, places blocks, eats and fights like a player would.
 * <p>
 * Work in progress, it is built in stages. See {@link #status()} for what is implemented and where it currently is.
 */
public interface IBeatProcess extends IBaritoneProcess {

    /**
     * Start playing
     */
    void start();

    /**
     * Stop playing. Safe to call at any time.
     */
    void stop();

    /**
     * @return A one line description of what it is currently doing
     */
    String status();
}
