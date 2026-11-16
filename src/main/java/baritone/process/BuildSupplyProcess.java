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
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * When {@link BuilderProcess} runs out of a material it hands over to this process, which gets the missing items
 * (mining, killing, smelting, crafting, see {@link Survival}) and then lets the build carry on where it was.
 */
public final class BuildSupplyProcess extends BaritoneProcessHelper {

    private static final int GIVE_UP_AFTER_TICKS = 20 * 60 * 6;

    private final Survival survival;
    private Map<Item, Integer> targets;
    private int lastDeficit;
    private int lastProgress;
    private int ticks;

    public BuildSupplyProcess(Baritone baritone) {
        super(baritone);
        this.survival = new Survival(baritone);
    }

    @Override
    public boolean isActive() {
        return baritone.getBuilderProcess().isAwaitingSupply();
    }

    @Override
    public double priority() {
        return 8; // above mining/walking, which it uses
    }

    @Override
    public void onLostControl() {
        // #cancel (or the game being left): forget everything
        if (targets != null) {
            survival.shutdown();
        }
        targets = null;
        ticks = 0;
    }

    @Override
    public String displayName0() {
        return "Approvisionnement de la construction: " + survival.step();
    }

    private PathingCommand giveUp(String why) {
        logDirect("Construction: " + why + " — je mets la construction en pause (#resume pour reprendre).");
        survival.shutdown();
        targets = null;
        baritone.getBuilderProcess().pause();
        baritone.getBuilderProcess().supplyDone();
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (ctx.player() == null || ctx.world() == null) {
            return new PathingCommand(null, PathingCommandType.DEFER);
        }
        if (ctx.player().isDeadOrDying()) {
            return giveUp("le joueur est mort");
        }
        survival.beginTick();
        ticks++;

        if (targets == null) {
            targets = new LinkedHashMap<>();
            for (Map.Entry<BlockState, Integer> entry : baritone.getBuilderProcess().getMissingMaterials().entrySet()) {
                Item item = entry.getKey().getBlock().asItem();
                if (item == Items.AIR) {
                    continue; // water, fire... can't be carried
                }
                targets.merge(item, entry.getValue(), Integer::sum);
            }
            // absolute amounts: what we have now plus what is missing
            Map<Item, Integer> absolute = new LinkedHashMap<>();
            targets.forEach((item, missing) -> absolute.put(item, survival.countItem(item) + missing));
            targets = absolute;
            ticks = 0;
            lastDeficit = Integer.MAX_VALUE;
            lastProgress = 0;
            if (targets.isEmpty()) {
                return giveUp("il manque des blocs qu'on ne peut pas ramasser");
            }
            StringBuilder what = new StringBuilder();
            targets.forEach((item, n) -> what.append(n).append(" ").append(item.getDescription().getString()).append(", "));
            logDirect("Construction: il manque des matériaux, je vais les chercher : " + what + "puis je reprends.");
        }

        PathingCommand basics = survival.basics();
        if (basics != null) {
            return basics;
        }
        if (survival.delegating()) {
            trackProgress();
            if (ticks - lastProgress > GIVE_UP_AFTER_TICKS) {
                return giveUp("je n'avance plus dans la recherche de matériaux");
            }
            return new PathingCommand(null, PathingCommandType.DEFER);
        }
        PathingCommand forage = survival.foraging();
        if (forage != null) {
            return forage;
        }

        // the first item we don't have enough of
        for (Map.Entry<Item, Integer> entry : targets.entrySet()) {
            PathingCommand command = survival.acquire(entry.getKey(), entry.getValue());
            if (survival.failure() != null) {
                return giveUp(survival.failure());
            }
            if (command != null) {
                trackProgress();
                if (ticks - lastProgress > GIVE_UP_AFTER_TICKS) {
                    return giveUp("je n'avance plus dans la recherche de matériaux");
                }
                return command;
            }
        }
        logDirect("Construction: matériaux réunis, je reprends la construction.");
        survival.shutdown();
        targets = null;
        baritone.getBuilderProcess().supplyDone();
        return new PathingCommand(null, PathingCommandType.DEFER);
    }

    private void trackProgress() {
        int deficit = 0;
        for (Map.Entry<Item, Integer> entry : targets.entrySet()) {
            deficit += Math.max(0, entry.getValue() - survival.countItem(entry.getKey()));
        }
        if (deficit < lastDeficit) {
            lastDeficit = deficit;
            lastProgress = ticks;
        }
    }
}
