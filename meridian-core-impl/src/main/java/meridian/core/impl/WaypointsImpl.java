package meridian.core.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import meridian.core.api.Marker;
import meridian.core.api.MarkerCategory;
import meridian.core.api.Player;
import meridian.core.api.Vec3;
import meridian.core.api.Waypoints;
import meridian.core.api.World;

/**
 * Saved places, as a plain list.
 *
 * <p>There is nothing underneath this but {@link MapMarkersImpl}: a waypoint is a local marker,
 * and the same pin shows up in both views. What this adds is the one thing a list of saved
 * places needs and a marker service should not care about - that a map with three hundred pins
 * on it is no map at all, so only the nearest handful are actually drawn, and which handful that
 * is changes as the player walks.
 *
 * <p>Keeping the two apart matters more than the code saved by merging them. A module that wants
 * to drop a pin should not have to know what a marker category is; a module managing the map
 * needs nothing else.
 */
final class WaypointsImpl implements Waypoints {

    /** Enough to be useful, few enough that the map stays readable. */
    private static final int DEFAULT_VISIBLE = 32;

    private final MapMarkersImpl markers;
    private final World world;

    private volatile int visibleLimit = DEFAULT_VISIBLE;

    WaypointsImpl(MapMarkersImpl markers, World world) {
        this.markers = markers;
        this.world = world;
    }

    @Override
    public Waypoint add(String name, Vec3 position, String icon, int colourRgb) {
        Waypoint added = toWaypoint(markers.createLocal(name, position, icon, colourRgb));
        project();
        return added;
    }

    @Override
    public boolean remove(UUID id) {
        boolean removed = markers.remove(MarkerCodec.LOCAL_PREFIX + id);
        if (removed) {
            project();
        }
        return removed;
    }

    @Override
    public List<Waypoint> all() {
        List<Waypoint> out = new ArrayList<>();
        for (Marker marker : markers.byCategory(MarkerCategory.LOCAL)) {
            Waypoint waypoint = toWaypoint(marker);
            if (waypoint != null) {
                out.add(waypoint);
            }
        }
        return out;
    }

    @Override
    public Optional<Waypoint> get(UUID id) {
        return markers.get(MarkerCodec.LOCAL_PREFIX + id).map(this::toWaypoint);
    }

    @Override
    public void setVisibleLimit(int limit) {
        visibleLimit = Math.max(0, limit);
        project();
    }

    @Override
    public int visibleLimit() {
        return visibleLimit;
    }

    /**
     * Works out which waypoints should be on the map right now. Run as the player moves: the
     * nearest ones are the ones worth drawing, and which those are changes with every step.
     */
    void project() {
        List<Marker> mine = markers.byCategory(MarkerCategory.LOCAL);
        if (mine.size() <= visibleLimit) {
            markers.setSuppressed(Set.of());
            return;
        }
        Player player = world.player().orElse(null);
        Vec3 here = player == null ? null : player.position();
        if (here == null) {
            // Without a position there is no "nearest", and guessing would make pins flicker on
            // and off for no reason. Leave the map as it is until the player turns up.
            return;
        }
        List<Marker> byDistance = new ArrayList<>(mine);
        byDistance.sort(Comparator.comparingDouble(m -> distanceSquared(here, m.position())));
        Set<String> tooFar = new HashSet<>();
        for (int i = visibleLimit; i < byDistance.size(); i++) {
            tooFar.add(byDistance.get(i).id());
        }
        markers.setSuppressed(tooFar);
    }

    private static double distanceSquared(Vec3 a, Vec3 b) {
        double dx = a.x() - b.x();
        double dz = a.z() - b.z();
        return dx * dx + dz * dz;   // height is not what makes a waypoint far away
    }

    /** A local marker seen as a waypoint, or null when its id is not one of ours. */
    private Waypoint toWaypoint(Marker marker) {
        UUID id = idOf(marker.id());
        return id == null ? null
                : new Waypoint(id, marker.name(), marker.position(), marker.icon(),
                        marker.colourRgb());
    }

    private static UUID idOf(String markerId) {
        if (markerId == null || !markerId.startsWith(MarkerCodec.LOCAL_PREFIX)) {
            return null;
        }
        try {
            return UUID.fromString(markerId.substring(MarkerCodec.LOCAL_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            return null;   // a local marker made by something else; not a waypoint
        }
    }
}
