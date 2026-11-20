package meridian.core.impl;

import io.netty.channel.ChannelHandlerContext;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.core.api.PacketTap;
import org.slf4j.Logger;

/**
 * Live {@link PacketTap}: two readers - one early on what comes from the server (before anything of
 * the proxy's changes it), one last on what goes to it (what actually went) - each packet a listener
 * wants written out to its bytes with the protocol's own {@code serialize}.
 */
final class PacketTapImpl implements PacketTap {

    private record Sub(Set<String> names, Listener listener) {
        boolean wants(String name) {
            return names.isEmpty() || names.contains(name);
        }
    }

    private final Logger log;
    private final CopyOnWriteArrayList<Sub> subs = new CopyOnWriteArrayList<>();

    PacketTapImpl(Logger log) {
        this.log = log;
    }

    @Override
    public AutoCloseable subscribe(Set<String> names, Listener listener) {
        Sub sub = new Sub(names == null ? Set.of() : Set.copyOf(names), listener);
        subs.add(sub);
        return () -> subs.remove(sub);
    }

    /** What comes from the server, read before it is changed. */
    PacketHandler fromServer() {
        return new PacketHandler() {
            @Override
            public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
                tap(false, packet);
                return Action.FORWARD;
            }
        };
    }

    /** What goes to the server, read as it went. */
    PacketHandler toServer() {
        return new PacketHandler() {
            @Override
            public Action handleC2S(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
                tap(true, packet);
                return Action.FORWARD;
            }
        };
    }

    private void tap(boolean toServer, Packet packet) {
        if (subs.isEmpty() || !(packet instanceof meridian.protocol.Packet p)) {
            return;
        }
        String name = p.getClass().getSimpleName();
        byte[] body = null;
        for (Sub sub : subs) {
            if (!sub.wants(name)) {
                continue;
            }
            try {
                if (body == null) {
                    body = bytes(p);
                }
                sub.listener().packet(toServer, p.getId(), name, body.clone());
            } catch (RuntimeException | Error e) {
                log.warn("meridian-core: a packet tap failed on {}", name, e);
            }
        }
    }

    private static byte[] bytes(meridian.protocol.Packet p) {
        int size = p.computeSize();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment mem = arena.allocate(Math.max(1, size));
            int written = p.serialize(mem, 0);
            return mem.asSlice(0, written).toArray(ValueLayout.JAVA_BYTE);
        }
    }
}
