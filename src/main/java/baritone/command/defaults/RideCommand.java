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
import baritone.api.command.datatypes.RelativeGoal;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.api.pathing.goals.Goal;
import baritone.api.process.IRideProcess;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class RideCommand extends Command {

    public RideCommand(IBaritone baritone) {
        super(baritone, "ride");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        final IRideProcess ride = baritone.getRideProcess();
        if (args.hasExactlyOne() && args.peekString().equals("stop")) {
            args.getString();
            ride.stop();
            logDirect("Stopped riding");
            return;
        }
        try {
            if (!args.hasAny()) {
                Goal goal = baritone.getCustomGoalProcess().mostRecentGoal();
                if (goal == null) {
                    throw new CommandInvalidStateException("No goal has been set. Use #ride <x> <z>, #ride <x> <y> <z>, or set a goal first.");
                }
                ride.rideTo(goal);
            } else {
                args.requireMax(3);
                ride.rideTo(args.getDatatypePost(RelativeGoal.INSTANCE, ctx.playerFeet()));
            }
        } catch (IllegalArgumentException | IllegalStateException ex) {
            throw new CommandInvalidStateException(ex.getMessage());
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        TabCompleteHelper helper = new TabCompleteHelper();
        if (args.hasExactlyOne()) {
            helper.append("stop");
        }
        return helper.filterPrefix(args.getString()).stream();
    }

    @Override
    public String getShortDesc() {
        return "Steer the horse / boat / pig you are riding to a goal";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Mount a tamed and saddled horse, donkey, mule or camel, a pig (holding a carrot on a stick), or a boat, then run this command.",
                "",
                "Baritone steers in a straight line towards the target and does simple look-ahead to avoid walls, drops and liquids",
                "(land mounts) or anything that isn't water or ice (boats). It is not a pathfinder, so it can get stuck behind big obstacles.",
                "",
                "Usage:",
                "> ride - Ride to the current goal",
                "> ride <x> <z> - Ride to an X,Z position",
                "> ride <x> <y> <z> - Ride to an X,Y,Z position (Y is ignored)",
                "> ride stop - Stop riding (does not dismount)",
                "",
                "Settings: rideArrivalRadius, rideDismountOnArrival"
        );
    }
}
