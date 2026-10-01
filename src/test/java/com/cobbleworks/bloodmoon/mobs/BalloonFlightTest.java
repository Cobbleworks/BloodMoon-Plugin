package com.cobbleworks.bloodmoon.mobs;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BalloonFlightTest {
    @Test
    void elevatedGlideDescendsAndReachesItsExactDestinationWithinTwoSeconds() {
        Location start = new Location(null, 0, 80, 0);
        Location destination = new Location(null, 16, 65, 8);
        int duration = BalloonFlight.duration(start, destination);
        assertTrue(duration <= 40);
        assertEquals(start, BalloonFlight.position(start, destination, 0, duration));
        assertTrue(BalloonFlight.position(start, destination, duration / 2, duration).getY() < start.getY());
        assertEquals(0, destination.toVector().distanceSquared(BalloonFlight.position(start, destination, duration, duration).toVector()), 1e-20);
    }

    @Test
    void longerFlightsAdvanceFartherEachTickInsteadOfNormalizingDistanceAway() {
        Location start = new Location(null, 0, 65, 0);
        double shortStep = BalloonFlight.position(start, start.clone().add(4, 0, 0), 1, 20).getX();
        double longStep = BalloonFlight.position(start, start.clone().add(12, 0, 0), 1, 20).getX();
        assertEquals(shortStep * 3, longStep, 1e-12);
    }
}
