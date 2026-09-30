package dev.pumpkinmc.patch.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.pumpkinmc.patch.core.capability.Capability;
import dev.pumpkinmc.patch.core.catalog.CatalogEntry;
import dev.pumpkinmc.patch.core.exec.CallWatchdog;
import dev.pumpkinmc.patch.core.manifest.Manifest;
import dev.pumpkinmc.patch.core.manifest.ManifestParser;
import dev.pumpkinmc.patch.core.manifest.SemVer;
import dev.pumpkinmc.patch.core.network.HandshakeNegotiator;
import dev.pumpkinmc.patch.core.network.MuxCodec;
import dev.pumpkinmc.patch.core.network.MuxFrame;
import dev.pumpkinmc.patch.core.network.MuxFrame.Status;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CoreUnitTest {

    @Test
    void muxFramesRoundTripAndMatchTheRustEncoding() throws Exception {
        var data = new MuxFrame.Data(300, 1, new byte[] {0, 1, 2, (byte) 255});
        byte[] encoded = MuxCodec.encode(data);
        // type 3, route 300 as VarInt (0xAC 0x02), channel 1, then the raw payload.
        assertArrayEquals(new byte[] {3, (byte) 0xAC, 2, 1, 0, 1, 2, (byte) 255}, encoded);
        var decoded = (MuxFrame.Data) MuxCodec.decode(encoded);
        assertEquals(300, decoded.route());
        assertArrayEquals(data.payload(), decoded.payload());

        var hello = new MuxFrame.Hello(1, "Pumpkin", List.of(new MuxFrame.HelloMod(
                "example:ping", "^0.1", "pumpkin:client/client-mod@0.1.0", true, "ping/1", List.of("a", "b"))));
        assertEquals(hello, MuxCodec.decode(MuxCodec.encode(hello)));
        var accept = new MuxFrame.Accept(true, "", List.of(new MuxFrame.Route("example:ping", 1)));
        assertEquals(accept, MuxCodec.decode(MuxCodec.encode(accept)));
    }

    @Test
    void malformedFramesAreRejected() {
        assertThrows(MuxCodec.MalformedFrame.class, () -> MuxCodec.decode(new byte[0]));
        assertThrows(MuxCodec.MalformedFrame.class, () -> MuxCodec.decode(new byte[] {9}));
        assertThrows(MuxCodec.MalformedFrame.class, () -> MuxCodec.decode(new byte[] {4, 1, 5, 'a'}));
        assertThrows(MuxCodec.MalformedFrame.class, () -> MuxCodec.decode(new byte[] {2, 0, 0, 0, 7}));
        // A HELLO announcing more mods than the limit.
        assertThrows(MuxCodec.MalformedFrame.class, () -> MuxCodec.decode(new byte[] {0, 1, 0, (byte) 0x81, 2}));
    }

    @Test
    void semverCaretRulesFollowCargo() {
        assertTrue(SemVer.parse("0.1.3").satisfies("^0.1"));
        assertFalse(SemVer.parse("0.2.0").satisfies("^0.1"));
        assertTrue(SemVer.parse("1.4.0").satisfies("^1.2"));
        assertFalse(SemVer.parse("1.1.0").satisfies("^1.2"));
        assertTrue(SemVer.parse("1.1.0").satisfies("*"));
        assertTrue(SemVer.parse("1.1.0").satisfies("=1.1.0"));
    }

    static final String MANIFEST = """
            manifest-version = 1
            [mod]
            id = "example:ping"
            name = "Ping"
            version = "0.1.0"
            [client]
            component = "client.wasm"
            sha256 = "ABC"
            world = "pumpkin:client/client-mod@0.1.0"
            [client.capabilities]
            required = ["net", "input"]
            [client.net]
            protocol = "ping/1"
            channels = ["request", "response"]
            [[client.actions]]
            id = "ping"
            default-key = "key.keyboard.o"
            """;

    @Test
    void manifestParsesAndValidates() {
        var ok = assertInstanceOf(ManifestParser.Result.Ok.class, ManifestParser.parse(MANIFEST));
        Manifest m = ok.manifest();
        assertEquals(Manifest.Activation.PUMPKIN_SERVER, m.activation());
        assertEquals(Set.of(Capability.NET, Capability.INPUT), m.required());
        assertEquals("abc", m.sha256());

        var noInput = ManifestParser.parse(MANIFEST.replace("\"net\", \"input\"", "\"net\""));
        assertInstanceOf(ManifestParser.Result.Invalid.class, noInput);
        var deps = ManifestParser.parse(MANIFEST + "\n[dependencies]\nx = 1\n");
        assertEquals("UNSUPPORTED_FEATURE", ((ManifestParser.Result.Invalid) deps).reason());
        var badVersion = ManifestParser.parse(MANIFEST.replace("manifest-version = 1", "manifest-version = 2"));
        assertEquals("UNSUPPORTED_MANIFEST", ((ManifestParser.Result.Invalid) badVersion).reason());
    }

    @Test
    void handshakeMatrix() {
        Manifest m = ((ManifestParser.Result.Ok) ManifestParser.parse(MANIFEST)).manifest();
        var compiled = new CatalogEntry(Path.of("x"), m, CatalogEntry.Status.COMPILED, "", null,
                Set.of(Capability.LOG, Capability.NET, Capability.INPUT), null, 0);
        var offer = new MuxFrame.HelloMod("example:ping", "^0.1", "pumpkin:client/client-mod@0.1.0", true, "ping/1",
                List.of("response", "request"));
        assertEquals(Status.AVAILABLE, status(List.of(compiled), offer));
        assertEquals(Status.MISSING, status(List.of(), offer));
        assertEquals(Status.INCOMPATIBLE_VERSION, status(List.of(compiled),
                new MuxFrame.HelloMod(offer.id(), "^1.0", offer.world(), true, offer.protocol(), offer.channels())));
        assertEquals(Status.INCOMPATIBLE_WORLD, status(List.of(compiled), new MuxFrame.HelloMod(offer.id(),
                offer.versionReq(), "pumpkin:client/client-mod@0.2.0", true, offer.protocol(), offer.channels())));
        assertEquals(Status.INCOMPATIBLE_PROTOCOL, status(List.of(compiled), new MuxFrame.HelloMod(offer.id(),
                offer.versionReq(), offer.world(), true, "ping/2", offer.channels())));
        assertEquals(Status.INCOMPATIBLE_PROTOCOL, status(List.of(compiled), new MuxFrame.HelloMod(offer.id(),
                offer.versionReq(), offer.world(), true, offer.protocol(), List.of("request"))));
        var rejected = new CatalogEntry(Path.of("x"), m, CatalogEntry.Status.REJECTED, "HASH_MISMATCH", null,
                Set.of(), null, 0);
        assertEquals(Status.REJECTED_LOCALLY, status(List.of(rejected), offer));
    }

    private static Status status(List<CatalogEntry> catalog, MuxFrame.HelloMod offer) {
        return HandshakeNegotiator.reply(catalog, new MuxFrame.Hello(1, "t", List.of(offer))).mods().getFirst().status();
    }

    @Test
    void watchdogInterruptsOnlyGuestCodeAndClearsTheFlag() throws Exception {
        try (var w = new CallWatchdog()) {
            // A budget that runs out while "host code" runs is held back until the host returns.
            w.arm(5_000_000L);
            w.enterHost();
            Thread.sleep(30);
            assertFalse(Thread.currentThread().isInterrupted(), "interrupt delivered inside host code");
            w.exitHost();
            assertTrue(Thread.currentThread().isInterrupted(), "interrupt not redelivered to the guest");
            assertTrue(w.disarm() != 0);
            assertFalse(Thread.currentThread().isInterrupted(), "interrupt leaked past disarm");

            // A call that finishes within budget is never interrupted.
            w.arm(50_000_000L);
            assertEquals(0, w.disarm());
            Thread.sleep(80);
            assertFalse(Thread.currentThread().isInterrupted());

            // A spinning "guest" is interrupted near the budget.
            long start = System.nanoTime();
            w.arm(10_000_000L);
            while (!Thread.currentThread().isInterrupted()) {
                Thread.onSpinWait();
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue(w.disarm() != 0);
            assertTrue(elapsedMs >= 9 && elapsedMs < 200, "fired after " + elapsedMs + " ms");
            assertFalse(Thread.currentThread().isInterrupted());
        }
    }
}
