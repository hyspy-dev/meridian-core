package meridian.core.impl.movement;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import meridian.core.api.Rotation;
import meridian.protocol.Hitbox;
import meridian.protocol.UpdateType;
import meridian.protocol.packets.assets.UpdateBlockHitboxes;

/**
 * The server's block collision shapes ({@code UpdateBlockHitboxes}): each block type names one by
 * index ({@code BlockType.hitbox}), and a placed block turns it by its rotation - the port of the
 * server's {@code BlockBoundingBoxes.rotate}. A shape is boxes in the block's own unit space; a
 * tall block's (a door's) reach past its cell. No boxes at all is the whole cell.
 */
public final class BlockHitboxes {

    /** The whole cell. */
    static final double[][] UNIT = {{0, 0, 0, 1, 1, 1}};

    private final Map<Integer, Hitbox[]> base = new ConcurrentHashMap<>();
    private final Map<Long, double[][]> turned = new ConcurrentHashMap<>();

    public void update(UpdateBlockHitboxes update) {
        if (update.type == UpdateType.Init) {
            base.clear();
        }
        if (update.blockBaseHitboxes != null) {
            update.blockBaseHitboxes.forEach((index, boxes) -> {
                if (update.type == UpdateType.Remove) {
                    base.remove(index);
                } else if (boxes != null) {
                    base.put(index, boxes);
                }
            });
        }
        turned.clear();
    }

    /** The boxes of shape {@code hitbox} turned by {@code rotation} (the server's index: roll*16 + pitch*4 + yaw). */
    double[][] boxes(int hitbox, int rotation) {
        long key = ((long) hitbox << 8) | (rotation & 0xFF);
        return turned.computeIfAbsent(key, k -> turn(base.get(hitbox), rotation));
    }

    static double[][] turn(Hitbox[] boxes, int rotation) {
        if (boxes == null || boxes.length == 0) {
            return UNIT;
        }
        int yaw = Rotation.yaw(rotation);
        int pitch = Rotation.pitch(rotation);
        int roll = Rotation.roll(rotation);
        double[][] out = new double[boxes.length][];
        for (int i = 0; i < boxes.length; i++) {
            Hitbox h = boxes[i];
            double[] b = {h.minX, h.minY, h.minZ, h.maxX, h.maxY, h.maxZ};
            switch (roll) {
                case 1 -> z270(b);
                case 2 -> z180(b);
                case 3 -> z90(b);
                default -> { }
            }
            switch (pitch) {
                case 1 -> x90(b);
                case 2 -> x180(b);
                case 3 -> x270(b);
                default -> { }
            }
            switch (yaw) {
                case 1 -> y90(b);
                case 2 -> y180(b);
                case 3 -> y270(b);
                default -> { }
            }
            out[i] = normalize(b);
        }
        return out;
    }

    // min x/y/z at 0/1/2, max at 3/4/5 - each turn applied to both corners, as the server does.
    private static void x90(double[] b) {
        for (int o = 0; o <= 3; o += 3) {
            double t = b[o + 2];
            b[o + 2] = b[o + 1];
            b[o + 1] = 1 - t;
        }
    }

    private static void x180(double[] b) {
        for (int o = 0; o <= 3; o += 3) {
            b[o + 2] = 1 - b[o + 2];
            b[o + 1] = 1 - b[o + 1];
        }
    }

    private static void x270(double[] b) {
        for (int o = 0; o <= 3; o += 3) {
            double t = b[o + 2];
            b[o + 2] = 1 - b[o + 1];
            b[o + 1] = t;
        }
    }

    private static void y90(double[] b) {
        for (int o = 0; o <= 3; o += 3) {
            double t = b[o];
            b[o] = b[o + 2];
            b[o + 2] = 1 - t;
        }
    }

    private static void y180(double[] b) {
        for (int o = 0; o <= 3; o += 3) {
            b[o] = 1 - b[o];
            b[o + 2] = 1 - b[o + 2];
        }
    }

    private static void y270(double[] b) {
        for (int o = 0; o <= 3; o += 3) {
            double t = b[o];
            b[o] = 1 - b[o + 2];
            b[o + 2] = t;
        }
    }

    private static void z90(double[] b) {
        for (int o = 0; o <= 3; o += 3) {
            double t = b[o];
            b[o] = b[o + 1];
            b[o + 1] = 1 - t;
        }
    }

    private static void z180(double[] b) {
        for (int o = 0; o <= 3; o += 3) {
            b[o] = 1 - b[o];
            b[o + 1] = 1 - b[o + 1];
        }
    }

    private static void z270(double[] b) {
        for (int o = 0; o <= 3; o += 3) {
            double t = b[o];
            b[o] = 1 - b[o + 1];
            b[o + 1] = t;
        }
    }

    private static double[] normalize(double[] b) {
        for (int a = 0; a < 3; a++) {
            if (b[a] > b[a + 3]) {
                double t = b[a];
                b[a] = b[a + 3];
                b[a + 3] = t;
            }
        }
        return b;
    }
}
