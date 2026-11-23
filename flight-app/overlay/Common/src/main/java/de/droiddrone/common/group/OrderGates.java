/*
 *  This file is part of DRAUPNE Flight, based on DroidDrone.
 *
 *  DroidDrone is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  DroidDrone is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with DroidDrone.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.droiddrone.common.group;

import de.droiddrone.common.SeqGate;

/**
 * A follower's phone: its commander's GROUP orders, EXIT plans and TRAFFIC, each a sequence of its own (SeqGate) - an
 * order delayed behind newer ones is dropped (it moved the follower back to where the group was; after a DISBAND it
 * even made the drone a follower again). New for each commander. Java 11.
 */
public final class OrderGates {
    public final SeqGate group = new SeqGate(), exit = new SeqGate(), traffic = new SeqGate();
}
