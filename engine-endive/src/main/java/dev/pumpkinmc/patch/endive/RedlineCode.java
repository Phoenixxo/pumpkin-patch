package dev.pumpkinmc.patch.endive;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import run.endive.cm.types.CoreModuleSection;
import run.endive.cm.types.WasmComponent;
import run.endive.redline.experimental.api.NativeCodeSerializer;
import run.endive.redline.experimental.api.internal.RedlineTarget;
import run.endive.redline.experimental.compiler.internal.NativeCompiler;
import run.endive.wasm.WasmModule;

/**
 * Native code for a component's core modules, compiled by Redline's Cranelift on this machine.
 *
 * <p>Only a core module that defines a linear memory runs natively. In a component built by
 * wit-component that is the main module, which holds all of the mod's code. The adapter modules
 * wit-component adds wire imports through a table that one instance fills and another calls
 * through. Redline calls a table entry with the caller's context, which is wrong for a function
 * another instance put there, so those modules stay on Endive's bytecode compiler. They only
 * forward calls.
 *
 * <p>Code is only ever compiled here, from the Wasm the mod ships. The memory safety of the result
 * comes from Cranelift emitting the bounds checks itself, so native code is never accepted from a
 * mod or a server. The cache holds what this host compiled and is keyed so that a different
 * component, CPU target, or Redline build misses it.
 */
final class RedlineCode {
    /** The classes whose contract with the native code a cached entry depends on. */
    private static final String[] FINGERPRINTED = {
        "run/endive/redline/experimental/compiler/internal/NativeCompiler.class",
        "run/endive/redline/experimental/compiler/internal/NativeEmitters.class",
        "run/endive/redline/experimental/api/internal/CtxBuffer.class",
        "run/endive/redline/experimental/runner/internal/NativeMachine.class",
        "run/endive/redline/experimental/bridge/internal/Cranelift.meta",
    };

    private static volatile String fingerprint;

    private RedlineCode() {}

    /** The Cranelift target triple for this machine, empty where Redline has no backend. */
    static Optional<String> hostTriple() {
        return RedlineTarget.detectHost().map(RedlineTarget::triple);
    }

    /** Whether {@code module} runs natively, which is when it defines its own linear memory. */
    static boolean runsNatively(WasmModule module) {
        return module.memorySection().map(m -> m.memoryCount() > 0).orElse(false);
    }

    /**
     * Native code for every core module of {@code component} that {@link #runsNatively}, keyed by
     * module identity.
     *
     * @param cacheDir where compiled code is kept between runs, or {@code null} to always compile
     */
    static Map<WasmModule, byte[][]> compile(WasmComponent component, byte[] bytes, String triple, Path cacheDir) {
        Map<WasmModule, byte[][]> code = new IdentityHashMap<>();
        String key = cacheDir == null ? null : sha256(bytes).substring(0, 32) + "-%d-" + triple + "-" + fingerprint();
        int index = 0;
        for (CoreModuleSection section : component.coreModuleSections()) {
            WasmModule module = section.module();
            if (!runsNatively(module)) {
                index++;
                continue;
            }
            Path file = key == null ? null : cacheDir.resolve(key.formatted(index) + ".cl4j");
            byte[][] compiled = file == null ? null : read(file);
            if (compiled == null) {
                compiled = NativeCompiler.compileAll(triple, module);
                if (file != null) {
                    write(file, compiled);
                }
            }
            code.put(module, compiled);
            index++;
        }
        return code;
    }

    private static byte[][] read(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(file)) {
            return NativeCodeSerializer.deserialize(in);
        } catch (IOException e) {
            // A truncated or foreign file is compiled again and overwritten.
            return null;
        }
    }

    private static void write(Path file, byte[][] code) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp)) {
                NativeCodeSerializer.serialize(code, out);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // The cache is an optimization. The code compiled this run is still used.
        }
    }

    /** A hash of the Redline build in use, so code from another build is never loaded. */
    static String fingerprint() {
        String f = fingerprint;
        if (f == null) {
            MessageDigest digest = sha256();
            for (String resource : FINGERPRINTED) {
                try (InputStream in = RedlineCode.class.getClassLoader().getResourceAsStream(resource)) {
                    if (in == null) {
                        throw new IllegalStateException("Redline resource missing: " + resource);
                    }
                    digest.update(in.readAllBytes());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            f = HexFormat.of().formatHex(digest.digest()).substring(0, 16);
            fingerprint = f;
        }
        return f;
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(sha256().digest(bytes));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
