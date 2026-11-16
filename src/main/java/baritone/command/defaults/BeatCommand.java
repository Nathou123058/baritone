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

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.api.process.IBeatProcess;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class BeatCommand extends Command {

    public BeatCommand(IBaritone baritone) {
        super(baritone, "beat");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        final IBeatProcess beat = baritone.getBeatProcess();
        if (args.hasAny()) {
            String sub = args.getString();
            args.requireMax(0);
            if (sub.equals("stop")) {
                beat.stop();
                return;
            }
            if (sub.equals("status")) {
                logDirect("Beat: " + beat.status());
                return;
            }
            if (!sub.equals("start")) {
                logDirect("Usage: #beat [start|stop|status]");
                return;
            }
        }
        beat.start();
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        TabCompleteHelper helper = new TabCompleteHelper();
        if (args.hasExactlyOne()) {
            helper.append("start", "stop", "status");
        }
        return helper.filterPrefix(args.getString()).stream();
    }

    @Override
    public String getShortDesc() {
        return "Play survival by itself, towards beating the game";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Plays survival from nothing, without cheats: it chops wood, crafts, mines, smelts, eats, hunts and fights monsters that get too close.",
                "",
                "Work in progress, built in stages. Implemented: it crafts anything it needs from the game recipes (full iron gear, shield, bucket, flint and steel, diamond pickaxe), gets obsidian, builds and lights a nether portal and enters it.",
                "Not implemented yet: the nether fortress, blaze rods, ender pearls, the stronghold, the End and the dragon.",
                "",
                "Use it in survival, with no tools in your inventory, and keep an eye on it: it has only been tested on paper.",
                "",
                "Usage:",
                "> beat - Start",
                "> beat stop - Stop (also stopped by #cancel)",
                "> beat status - What it is doing right now"
        );
    }
}
