package dev.pumpkinmc.patch.core.network;

import dev.pumpkinmc.patch.core.catalog.CatalogEntry;
import dev.pumpkinmc.patch.core.manifest.Manifest;
import dev.pumpkinmc.patch.core.manifest.SemVer;
import dev.pumpkinmc.patch.core.network.MuxFrame.Hello;
import dev.pumpkinmc.patch.core.network.MuxFrame.HelloMod;
import dev.pumpkinmc.patch.core.network.MuxFrame.Reply;
import dev.pumpkinmc.patch.core.network.MuxFrame.ReplyMod;
import dev.pumpkinmc.patch.core.network.MuxFrame.Status;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/** A pure function from the local catalog and a server {@code HELLO} to a {@code REPLY}. */
public final class HandshakeNegotiator {
    private HandshakeNegotiator() {}

    public static Reply reply(List<CatalogEntry> catalog, Hello hello) {
        List<ReplyMod> mods = new ArrayList<>();
        for (HelloMod offered : hello.mods()) {
            mods.add(answer(catalog, offered));
        }
        return new Reply(MuxFrame.MUX_VERSION, List.copyOf(mods));
    }

    private static ReplyMod answer(List<CatalogEntry> catalog, HelloMod offered) {
        Optional<CatalogEntry> found = catalog.stream()
                .filter(e -> e.manifest() != null && e.id().equals(offered.id()))
                .findFirst();
        if (found.isEmpty()) {
            return new ReplyMod(offered.id(), Status.MISSING, "", "not installed");
        }
        CatalogEntry e = found.get();
        Manifest m = e.manifest();
        switch (e.status()) {
            case REJECTED -> {
                return new ReplyMod(offered.id(), Status.REJECTED_LOCALLY, m.version(), e.reason());
            }
            case DISABLED -> {
                return new ReplyMod(offered.id(), Status.DISABLED_BY_USER, m.version(), "");
            }
            case RESOLVED -> {
                return new ReplyMod(offered.id(), Status.REJECTED_LOCALLY, m.version(), "not compiled yet");
            }
            case COMPILED -> {}
        }
        SemVer version = SemVer.parse(m.version());
        if (version == null || !version.satisfies(offered.versionReq())) {
            return new ReplyMod(offered.id(), Status.INCOMPATIBLE_VERSION, m.version(),
                    m.version() + " is installed, the server requires " + offered.versionReq());
        }
        if (!sameMinorWorld(m.world(), offered.world())) {
            return new ReplyMod(offered.id(), Status.INCOMPATIBLE_WORLD, m.version(),
                    "installed for " + m.world() + ", server offers " + offered.world());
        }
        if (!m.protocol().equals(offered.protocol())) {
            return new ReplyMod(offered.id(), Status.INCOMPATIBLE_PROTOCOL, m.version(),
                    "protocol " + m.protocol() + ", server uses " + offered.protocol());
        }
        if (!new HashSet<>(m.channels()).equals(new HashSet<>(offered.channels()))) {
            return new ReplyMod(offered.id(), Status.INCOMPATIBLE_PROTOCOL, m.version(),
                    "channels " + m.channels() + ", server uses " + offered.channels());
        }
        if (!e.granted().containsAll(m.required())) {
            return new ReplyMod(offered.id(), Status.REJECTED_LOCALLY, m.version(), "required capability denied");
        }
        return new ReplyMod(offered.id(), Status.AVAILABLE, m.version(), "");
    }

    private static boolean sameMinorWorld(String local, String offered) {
        int a = local.lastIndexOf('.');
        int b = offered.lastIndexOf('.');
        return a > 0 && b > 0 && local.substring(0, a).equals(offered.substring(0, b));
    }
}
