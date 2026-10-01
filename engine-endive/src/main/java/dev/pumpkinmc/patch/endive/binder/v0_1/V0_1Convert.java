package dev.pumpkinmc.patch.endive.binder.v0_1;

import dev.pumpkinmc.patch.core.model.Event;
import dev.pumpkinmc.patch.core.model.HostError;
import dev.pumpkinmc.patch.core.model.Model;
import dev.pumpkinmc.patch.endive.binder.v0_1.exports.pumpkin.client.guest.ActionEvent;
import dev.pumpkinmc.patch.endive.binder.v0_1.exports.pumpkin.client.guest.DrawCommand;
import dev.pumpkinmc.patch.endive.binder.v0_1.exports.pumpkin.client.guest.EventKinds;
import dev.pumpkinmc.patch.endive.binder.v0_1.exports.pumpkin.client.guest.FrameInfo;
import dev.pumpkinmc.patch.endive.binder.v0_1.exports.pumpkin.client.guest.FrameOutput;
import dev.pumpkinmc.patch.endive.binder.v0_1.exports.pumpkin.client.guest.InitInfo;
import dev.pumpkinmc.patch.endive.binder.v0_1.exports.pumpkin.client.guest.InitResult;
import dev.pumpkinmc.patch.endive.binder.v0_1.exports.pumpkin.client.guest.NetPayload;
import dev.pumpkinmc.patch.endive.binder.v0_1.exports.pumpkin.client.guest.SessionInfo;
import dev.pumpkinmc.patch.endive.binder.v0_1.exports.pumpkin.client.guest.TickInfo;
import dev.pumpkinmc.patch.endive.binder.v0_1.pumpkin.base.types.Uuid;
import dev.pumpkinmc.patch.endive.binder.v0_1.pumpkin.base.types.Vec3;
import dev.pumpkinmc.patch.endive.binder.v0_1.pumpkin.client.model.EntitySnapshot;
import dev.pumpkinmc.patch.endive.binder.v0_1.pumpkin.client.model.PlayerSnapshot;
import dev.pumpkinmc.patch.endive.binder.v0_1.pumpkin.client.model.WorldRef;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Wire types to core records and back. The only mapping code for this WIT version. */
final class V0_1Convert {
    private V0_1Convert() {}

    static dev.pumpkinmc.patch.endive.binder.v0_1.pumpkin.base.types.HostError error(HostError e) {
        return switch (e.code()) {
            case DENIED -> new dev.pumpkinmc.patch.endive.binder.v0_1.pumpkin.base.types.HostError.Denied();
            case INVALID_ARGUMENT ->
                new dev.pumpkinmc.patch.endive.binder.v0_1.pumpkin.base.types.HostError.InvalidArgument(e.detail());
            case UNAVAILABLE -> new dev.pumpkinmc.patch.endive.binder.v0_1.pumpkin.base.types.HostError.Unavailable();
            case STALE -> new dev.pumpkinmc.patch.endive.binder.v0_1.pumpkin.base.types.HostError.Stale();
            case LIMIT_EXCEEDED ->
                new dev.pumpkinmc.patch.endive.binder.v0_1.pumpkin.base.types.HostError.LimitExceeded(e.detail());
        };
    }

    static PlayerSnapshot player(Model.PlayerSnapshot p) {
        return new PlayerSnapshot(
                new Uuid(p.id().getMostSignificantBits(), p.id().getLeastSignificantBits()),
                p.name(),
                vec3(p.pos()),
                p.yaw(),
                p.pitch(),
                p.health(),
                world(p.world()));
    }

    static EntitySnapshot entity(Model.EntitySnapshot e, long kind) {
        return new EntitySnapshot(
                new Uuid(e.id().getMostSignificantBits(), e.id().getLeastSignificantBits()), kind, vec3(e.pos()));
    }

    static WorldRef world(Model.WorldRef w) {
        return new WorldRef(w.dimension(), Integer.toUnsignedLong(w.epoch()));
    }

    private static Vec3 vec3(Model.Vec3 v) {
        return new Vec3(v.x(), v.y(), v.z());
    }

    private static SessionInfo session(Model.SessionInfo s) {
        return new SessionInfo(BigInteger.valueOf(s.sessionId()), s.pumpkinServer(), s.netNegotiated());
    }

    static InitInfo initInfo(Model.InitInfo i) {
        return new InitInfo(session(i.session()), i.modVersion(), i.hostVersion());
    }

    static Model.InitResult initResult(InitResult r) {
        Set<Model.EventKind> kinds = EnumSet.noneOf(Model.EventKind.class);
        EventKinds flags = r.subscriptions();
        if (flags.has(EventKinds.Flag.TICK)) {
            kinds.add(Model.EventKind.TICK);
        }
        if (flags.has(EventKinds.Flag.WORLD)) {
            kinds.add(Model.EventKind.WORLD);
        }
        if (flags.has(EventKinds.Flag.INPUT)) {
            kinds.add(Model.EventKind.INPUT);
        }
        return new Model.InitResult(kinds);
    }

    static List<dev.pumpkinmc.patch.endive.binder.v0_1.exports.pumpkin.client.guest.Event> events(List<Event> batch) {
        var out = new ArrayList<dev.pumpkinmc.patch.endive.binder.v0_1.exports.pumpkin.client.guest.Event>(
                batch.size());
        for (Event e : batch) {
            out.add(
                    switch (e) {
                        case Event.SessionStarted s ->
                            new dev.pumpkinmc.patch.endive.binder.v0_1.exports.pumpkin.client.guest.Event
                                    .SessionStarted(session(s.info()));
                        case Event.WorldChanged w ->
                            new dev.pumpkinmc.patch.endive.binder.v0_1.exports.pumpkin.client.guest.Event.WorldChanged(
                                    new dev.pumpkinmc.patch.endive.binder.v0_1.exports.pumpkin.client.guest.WorldRef(
                                            w.world().dimension(), Integer.toUnsignedLong(w.world().epoch())));
                        case Event.Tick t ->
                            new dev.pumpkinmc.patch.endive.binder.v0_1.exports.pumpkin.client.guest.Event.Tick(
                                    new TickInfo(BigInteger.valueOf(t.gameTick())));
                        case Event.NetMessage m ->
                            new dev.pumpkinmc.patch.endive.binder.v0_1.exports.pumpkin.client.guest.Event.NetMessage(
                                    new NetPayload(m.channel(), m.payload()));
                        case Event.Action a ->
                            new dev.pumpkinmc.patch.endive.binder.v0_1.exports.pumpkin.client.guest.Event.Action(
                                    new ActionEvent(a.actionId(), a.pressed()));
                        case Event.SessionEnding x ->
                            new dev.pumpkinmc.patch.endive.binder.v0_1.exports.pumpkin.client.guest.Event
                                    .SessionEnding();
                    });
        }
        return out;
    }

    static FrameInfo frameInfo(Model.FrameInfo f) {
        return new FrameInfo(
                Integer.toUnsignedLong(f.guiWidth()),
                Integer.toUnsignedLong(f.guiHeight()),
                BigInteger.valueOf(f.gameTick()));
    }

    static Model.FrameOutput frameOutput(FrameOutput out) {
        return switch (out) {
            case FrameOutput.Unchanged u -> new Model.FrameOutput.Unchanged();
            case FrameOutput.Clear c -> new Model.FrameOutput.Clear();
            case FrameOutput.Commands c -> {
                List<Model.DrawCommand> commands = new ArrayList<>(c.value().size());
                for (DrawCommand d : c.value()) {
                    commands.add(
                            switch (d) {
                                case DrawCommand.Text t ->
                                    new Model.DrawCommand.Text(
                                            t.value().x(),
                                            t.value().y(),
                                            t.value().text(),
                                            t.value().argb().intValue(),
                                            t.value().shadow());
                                case DrawCommand.FillRect r ->
                                    new Model.DrawCommand.FillRect(
                                            r.value().x(),
                                            r.value().y(),
                                            (int) Math.min(r.value().w(), Integer.MAX_VALUE),
                                            (int) Math.min(r.value().h(), Integer.MAX_VALUE),
                                            r.value().argb().intValue());
                                default -> throw new IllegalStateException("unknown draw command " + d);
                            });
                }
                yield new Model.FrameOutput.Commands(commands);
            }
            default -> throw new IllegalStateException("unknown frame output " + out);
        };
    }
}
