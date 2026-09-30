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
import java.util.Set;
import java.util.function.Function;
import run.endive.cm.runtime.ComponentStore;
import run.endive.cm.types.WasmComponent;
import run.endive.runtime.Instance;
import run.endive.runtime.InterpreterMachine;
import run.endive.runtime.Memory;
import run.endive.runtime.Machine;

/** A parsed and checked component. Each instantiation gets a store of its own. */
final class EndiveCompiledComponent implements CompiledComponent {
    private final ComponentSource source;
    private final WasmComponent component;
    private final Function<Instance, Machine> machines;
    private final Set<String> imports;
    private final V0_1Binder binder;

    EndiveCompiledComponent(
            ComponentSource source,
            WasmComponent component,
            Function<Instance, Machine> machines,
            Set<String> imports,
            V0_1Binder binder) {
        this.source = source;
        this.component = component;
        this.machines = machines;
        this.imports = imports;
        this.binder = binder;
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
