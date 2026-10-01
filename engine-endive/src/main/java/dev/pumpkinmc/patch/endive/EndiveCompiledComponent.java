package dev.pumpkinmc.patch.endive;

import dev.pumpkinmc.patch.core.runtime.Runtime.CompiledComponent;
import dev.pumpkinmc.patch.core.runtime.Runtime.ComponentSource;
import dev.pumpkinmc.patch.core.runtime.Runtime.GuestInstance;
import dev.pumpkinmc.patch.core.runtime.Runtime.HostImports;
import dev.pumpkinmc.patch.core.runtime.Runtime.InstantiationFailure;
import dev.pumpkinmc.patch.endive.binder.v0_1.V0_1Binder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import run.endive.cm.runtime.ComponentStore;
import run.endive.cm.types.WasmComponent;
import run.endive.redline.experimental.compiler.internal.NativeCompiler;
import run.endive.redline.experimental.runner.NativeMachineFactory;
import run.endive.runtime.Instance;
import run.endive.runtime.InterpreterMachine;
import run.endive.runtime.Memory;
import run.endive.runtime.Machine;
import run.endive.wasm.WasmModule;

/** A parsed and checked component. Each instantiation gets a store of its own. */
final class EndiveCompiledComponent implements CompiledComponent {
    private final ComponentSource source;
    private final WasmComponent component;
    private final Function<Instance, Machine> machines;
    private final Map<WasmModule, byte[][]> nativeCode;
    private final String triple;
    private final Set<String> imports;
    private final V0_1Binder binder;

    EndiveCompiledComponent(
            ComponentSource source,
            WasmComponent component,
            Function<Instance, Machine> machines,
            Set<String> imports,
            V0_1Binder binder) {
        this(source, component, machines, null, null, imports, binder);
    }

    private EndiveCompiledComponent(
            ComponentSource source,
            WasmComponent component,
            Function<Instance, Machine> machines,
            Map<WasmModule, byte[][]> nativeCode,
            String triple,
            Set<String> imports,
            V0_1Binder binder) {
        this.source = source;
        this.component = component;
        this.machines = machines;
        this.nativeCode = nativeCode;
        this.triple = triple;
        this.imports = imports;
        this.binder = binder;
    }

    /**
     * A component whose memory-owning core modules run as native code Redline compiled for {@code
     * triple}, and whose other modules run on {@code machines}.
     */
    static EndiveCompiledComponent redline(
            ComponentSource source,
            WasmComponent component,
            Map<WasmModule, byte[][]> nativeCode,
            Function<Instance, Machine> machines,
            String triple,
            Set<String> imports,
            V0_1Binder binder) {
        return new EndiveCompiledComponent(source, component, machines, nativeCode, triple, imports, binder);
    }

    @Override
    public String world() {
        return source.declaredWorld();
    }

    @Override
    public Set<String> importedInterfaces() {
        return imports;
    }

    @Override
    public GuestInstance instantiate(HostImports hostImports) throws InstantiationFailure {
        if (nativeCode != null) {
            return instantiateNative(hostImports);
        }
        // Every core instance in the store passes through its machine factory, which is the only
        // place the runtime exposes them. They are kept to read linear memory sizes.
        List<Instance> cores = new ArrayList<>();
        Function<Instance, Machine> factory = instance -> {
            cores.add(instance);
            return machines != null ? machines.apply(instance) : new InterpreterMachine(instance);
        };
        try {
            return binder.instantiate(new ComponentStore(factory), component, hostImports, () -> memoryBytes(cores));
        } catch (RuntimeException e) {
            throw new InstantiationFailure(source.modId() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Instantiates on Redline. Its native code addresses memory, tables, and globals of its own
     * kinds, so a native core instance is built by Redline's factory rather than given a machine.
     */
    private GuestInstance instantiateNative(HostImports hostImports) throws InstantiationFailure {
        List<Memory> memories = new ArrayList<>();
        ComponentStore store = ComponentStore.withCoreInstances(module -> {
            if (!RedlineCode.runsNatively(module)) {
                return Instance.builder(module).withMachineFactory(machines);
            }
            return NativeMachineFactory.builder(module)
                    .withPrecompiledCode(nativeCode.get(module))
                    // Only a module the component's sections do not name would reach this.
                    .withCompilerFunction(m -> NativeCompiler.compileAll(triple, m))
                    .toInstanceBuilder()
                    .withMemoryFactory(limits -> {
                        Memory memory = NativeMachineFactory.createMemory(limits);
                        memories.add(memory);
                        return memory;
                    });
        });
        try {
            return binder.instantiate(store, component, hostImports, () -> memoryBytes(memories));
        } catch (RuntimeException e) {
            throw new InstantiationFailure(source.modId() + ": " + e.getMessage(), e);
        }
    }

    private static long memoryBytes(Iterable<Memory> memories) {
        long total = 0;
        for (Memory m : memories) {
            total += (long) m.pages() * 65_536;
        }
        return total;
    }

    private static long memoryBytes(List<Instance> cores) {
        Set<Memory> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        long total = 0;
        for (Instance core : cores) {
            Memory m = core.memory();
            if (m != null && seen.add(m)) {
                total += (long) m.pages() * 65_536;
            }
        }
        return total;
    }
}
