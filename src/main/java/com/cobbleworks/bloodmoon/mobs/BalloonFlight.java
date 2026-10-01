package com.cobbleworks.bloodmoon.mobs;

import org.bukkit.Location;

/** A finite glide ending at its ground destination, independent of entity gravity. */
final class BalloonFlight {
    private BalloonFlight() { }

    static int duration(Location start, Location destination) {
        return Math.max(12, Math.min(40, (int) Math.ceil(start.toVector().distance(destination.toVector()) / 0.7)));
    }

    static Location position(Location start, Location destination, int tick, int duration) {
        double fraction = Math.min(1.0, Math.max(0.0, (double) tick / duration));
        Location result = start.clone().add(destination.toVector().subtract(start.toVector()).multiply(fraction));
        result.add(0, Math.sin(Math.PI * fraction) * 1.2, 0);
        return result;
    }
}
