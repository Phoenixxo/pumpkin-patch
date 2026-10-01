package dev.pumpkinmc.patch.endive;

import dev.pumpkinmc.patch.core.runtime.Runtime.CompileException;
import dev.pumpkinmc.patch.core.runtime.Runtime.CompileException.Reason;
import dev.pumpkinmc.patch.core.runtime.Runtime.CompiledComponent;
import dev.pumpkinmc.patch.core.runtime.Runtime.ComponentRuntime;
import dev.pumpkinmc.patch.core.runtime.Runtime.ComponentSource;
import dev.pumpkinmc.patch.core.runtime.Runtime.RuntimeLimits;
import dev.pumpkinmc.patch.endive.binder.v0_1.V0_1Binder;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import run.endive.cm.parser.ComponentParser;
import run.endive.cm.parser.ComponentValidationException;
import run.endive.cm.parser.Validator;
import run.endive.cm.tools.ComponentValidate;
import run.endive.cm.tools.ComponentValidateException;
import run.endive.cm.types.CoreModuleSection;
import run.endive.cm.types.Export;
import run.endive.cm.types.ExportSection;
import run.endive.cm.types.Import;
import run.endive.cm.types.ImportSection;
import run.endive.cm.types.WasmComponent;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.runtime.Instance;
import run.endive.runtime.Machine;
import run.endive.wasm.WasmModule;

/** {@link ComponentRuntime} on Endive CM. The only class tree that knows the runtime's types. */
public final class EndiveComponentRuntime implements ComponentRuntime {

    /** How core modules execute. */
    public enum Engine {
        /** Endive's interpreter. */
        INTERPRETER,
        /** Endive's runtime compiler, which translates each core module to JVM bytecode. */
        COMPILER,
        /**
         * Redline, which compiles each core module to native code with Cranelift. It needs one of
         * Redline's targets and Java 25. Elsewhere this engine falls back to {@link #COMPILER}.
         */
        REDLINE
    }

    private static final String GUEST_EXPORT = "pumpkin:client/guest@";
    private static final Validator VALIDATOR = new Validator() {
        @Override
        public void validateBinary(InputStream wasm) throws ComponentValidationException {
            try {
                ComponentValidate.validate(wasm, "cm-implements");
            } catch (ComponentValidateException e) {
                throw new ComponentValidationException(e.getMessage(), e);
            }
        }
    };

    private final Engine engine;
    private final boolean validate;
    private final String redlineTriple;
    private final Path redlineCache;

    public EndiveComponentRuntime(Engine engine, boolean validate) {
        this(engine, validate, null);
    }

    /**
     * @param redlineCache where {@link Engine#REDLINE} keeps native code between runs, or {@code
     *     null} to compile every time
     */
    public EndiveComponentRuntime(Engine engine, boolean validate, Path redlineCache) {
        String triple = engine == Engine.REDLINE ? RedlineCode.hostTriple().orElse(null) : null;
        this.engine = engine == Engine.REDLINE && triple == null ? Engine.COMPILER : engine;
        this.validate = validate;
        this.redlineTriple = triple;
        this.redlineCache = redlineCache;
    }

    /** The engine core modules run on, which is not the one asked for when Redline is unsupported. */
    public Engine engine() {
        return engine;
    }

    @Override
    public String describe() {
        String name = engine == Engine.REDLINE ? "redline " + redlineTriple : engine.name().toLowerCase();
        return "Endive CM (" + name + (validate ? ", validated" : "") + ")";
    }

    @Override
    public CompiledComponent compile(ComponentSource source, RuntimeLimits limits) throws CompileException {
        WasmComponent component;
        try {
            component = ComponentParser.builder()
                    .withValidation(validate)
                    .withValidator(validate ? VALIDATOR : null)
                    .build()
                    .parse(() -> new ByteArrayInputStream(source.bytes()));
        } catch (RuntimeException e) {
            throw new CompileException(Reason.PARSE_ERROR, source.modId() + ": " + e.getMessage(), e);
        }

        Set<String> imports = new LinkedHashSet<>();
        for (ImportSection section : component.importSections()) {
            for (Import i : section.imports()) {
                imports.add(i.name());
            }
        }
        Set<String> exports = new LinkedHashSet<>();
        for (ExportSection section : component.exportSections()) {
            for (Export e : section.exports()) {
                exports.add(e.name());
            }
        }
        V0_1Binder binder = BinderRegistry.lookup(source.declaredWorld());
        ComponentInspector.check(source, imports, exports, binder.minorVersion());

        if (engine == Engine.REDLINE) {
            Map<WasmModule, byte[][]> code;
            try {
                code = RedlineCode.compile(component, source.bytes(), redlineTriple, redlineCache);
            } catch (RuntimeException e) {
                throw new CompileException(Reason.PARSE_ERROR, source.modId() + ": native compile failed: " + e, e);
            }
            return EndiveCompiledComponent.redline(
                    source, component, code, compileModules(component), redlineTriple, Set.copyOf(imports), binder);
        }
        Function<Instance, Machine> machines = engine == Engine.COMPILER ? compileModules(component) : null;
        return new EndiveCompiledComponent(source, component, machines, Set.copyOf(imports), binder);
    }

    /**
     * Compiles each core module to bytecode once, so every later instance reuses the classes. The
     * linker hands the parsed module to each core instance, so the module identity is the key.
     */
    private static Function<Instance, Machine> compileModules(WasmComponent component) {
        Map<WasmModule, Function<Instance, Machine>> compiled = new IdentityHashMap<>();
        for (CoreModuleSection section : component.coreModuleSections()) {
            WasmModule module = section.module();
            compiled.put(module, MachineFactoryCompiler.compile(module));
        }
        return instance -> {
            Function<Instance, Machine> factory = compiled.get(instance.module());
            if (factory == null) {
                synchronized (compiled) {
                    factory = compiled.computeIfAbsent(instance.module(), MachineFactoryCompiler::compile);
                }
            }
            return factory.apply(instance);
        };
    }

    /** Rejects a component whose imports and exports do not fit the declared world. */
    static final class ComponentInspector {
        private ComponentInspector() {}

        static void check(ComponentSource source, Set<String> imports, Set<String> exports, String minor)
                throws CompileException {
            String guest = exports.stream()
                    .filter(e -> e.startsWith(GUEST_EXPORT))
                    .findFirst()
                    .orElseThrow(() -> new CompileException(
                            Reason.WORLD_MISMATCH,
                            source.modId() + " does not export pumpkin:client/guest (exports: " + exports + ")",
                            null));
            Set<String> versions = new LinkedHashSet<>();
            versions.add(minorOf(guest));
            for (String i : imports) {
                if (!i.startsWith("pumpkin:client/") && !i.startsWith("pumpkin:base/")) {
                    throw new CompileException(
                            Reason.UNSUPPORTED_IMPORT,
                            source.modId() + " imports " + i + ". Only pumpkin:client and pumpkin:base are provided",
                            null);
                }
                versions.add(minorOf(i));
            }
            if (versions.size() > 1) {
                throw new CompileException(
                        Reason.MIXED_VERSIONS, source.modId() + " mixes pumpkin versions " + versions, null);
            }
            if (!versions.contains(minor)) {
                throw new CompileException(
                        Reason.WORLD_MISMATCH,
                        source.modId() + " declares " + source.declaredWorld() + " but its interfaces are " + versions,
                        null);
            }
        }

        private static String minorOf(String id) {
            int at = id.indexOf('@');
            if (at < 0) {
                return "unversioned";
            }
            String v = id.substring(at + 1);
            int second = v.indexOf('.', v.indexOf('.') + 1);
            return second < 0 ? v : v.substring(0, second);
        }
    }

    /** Declared world id to binder. */
    static final class BinderRegistry {
        private BinderRegistry() {}

        static V0_1Binder lookup(String world) throws CompileException {
            if (world.startsWith("pumpkin:client/client-mod@0.1.")) {
                return V0_1Binder.INSTANCE;
            }
            throw new CompileException(
                    Reason.UNSUPPORTED_WORLD, world + " is not supported. This host supports pumpkin:client 0.1", null);
        }
    }
}
