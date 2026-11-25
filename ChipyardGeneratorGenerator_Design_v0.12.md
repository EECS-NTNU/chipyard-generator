# ChipyardGeneratorGenerator
## Dependency-driven hardware generator integration

**Version:** 0.12 — working design draft  
**Date:** 25 November 2026  
**Status:** Architecture discussion; not an implemented API

This is a living design document. Requirements, decisions, and open questions are separated so that we can revise the architecture without losing the rationale behind it. The examples describe the intended developer experience unless explicitly identified as existing functionality.

## 1. Problem statement

Chipyard currently acts as the central integration repository for hardware generators. Its documented custom-generator workflow involves adding a generator to the Chipyard checkout, registering it in the top-level `build.sbt`, declaring its dependencies, and integrating it into the system configuration. This encourages project-specific Chipyard integration checkouts or forks, although a permanent fork is not technically required.

For a hardware researcher developing an accelerator, branch target buffer (BTB), cache, or other component, the contribution should instead be an independent project. The researcher should be able to describe the host system and the required generators in that project, then build the complete system without manually assembling or modifying a Chipyard checkout.

This is especially valuable when evaluating several independently developed, open-source architectures in a common environment.

## 2. Vision and design principles

Transform Chipyard from a repository-centric hardware integration framework into a project-centric hardware composition framework. Chipyard and the other generators become dependencies of the research project, rather than the research project becoming a submodule of Chipyard.

The tool and the user workspace have independent lifecycles. ChipyardGeneratorGenerator is released from its own repository. A workspace pins a tool version and supplies the inputs for a hardware project. The same tool release should build independently authored workspaces for a cache, accelerator, core or complete system.

| Owner | Responsibility |
|---|---|
| Tool | Read workspace declarations, resolve and lock dependencies, select sources, and invoke elaboration and backends. Versioned integration recipes describe the supported platform conventions. |
| User workspace | Own the dependency declarations and lockfile, local hardware sources, project-specific integrations and experiment definitions. Declare external generators and adapters by dependency. |
| Generated build workspace | Hold the derived build files and layout needed by the selected platform and backend. The tool can recreate this directory from the user workspace, locked inputs and toolchain. |

The BTB and BOOM examples illustrate internal component replacement. The tool's entry points and dependency model apply equally to whole-generator integration and standalone elaboration.

The user workspace has three identifiable entry points:

| Entry point | Responsibility | Analogy |
|---|---|---|
| `build.sbt` | Authoritatively declare the project-wide external dependencies and toolchain, independent of experiment selection. | Build system / package manifest |
| `Module.scala` | Define the top-level hardware generator exported by this project. | `main.c` as the canonical program entry point |
| `config/<experiment>/System.scala` | Define only the selected evaluation or deployment system and how its hardware is instantiated. | System assembly / integration configuration |

`Module.scala` is not simply a convenient name for an arbitrary implementation file. It is the **canonical hardware entry-point file** of a conforming project. The framework should know where to find the exported hardware generator, just as a conventional software toolchain knows how to locate a program's entry point. The distinction is that Chisel hardware must be constructed during elaboration and may be instantiated repeatedly; it is not a process that executes a `main()` function once.

Core principles:

- The research project owns its source code, dependencies, and system configurations.
- `build.sbt` defines what is available through one project-wide set of direct declarations and its resolved transitive dependency graph; the selected `System.scala` defines what is used. All experiments and standalone builds share that graph. No experiment-specific dependency declarations or implicit dependency discovery or fetching from system code.
- Available adapters and configuration fragments are passive: only the selected system invokes the factories needed for its hardware.
- System configurations are independently selectable experiment entry points, not a catalogue of every supported integration.
- Hardware implementations should be reusable independently of a particular host system whenever practical.
- Remote source dependencies are fetched automatically and cached; local source dependencies support active development.
- Exact source revisions and compatible toolchain versions are recorded for reproducibility. Explicit dependency aliases permit multiple revisions of the same generator in the project-wide graph; shared platform dependencies must remain compatible.
- Use existing Scala, sbt, Chisel, Diplomacy, and Chipyard functionality before adding new abstractions.
- Do not silently modify upstream hardware to resolve integration problems. Non-Chisel wrappers are required only to support whole-generator integration. Any internal replacement must use an existing or wrapper-provided extension point without requiring a new post-elaboration transformation pipeline. Such a pipeline is deferred to future work.

## 3. Requirements

### 3.1 Functional requirements

| ID | Requirement |
|---|---|
| FR-01 | Build a hardware system from an independent project root without manually registering the project in Chipyard. |
| FR-02 | Resolve generators from Git repositories, local directories, and eventually published Scala artifacts. |
| FR-03 | Resolve transitive source and library dependencies, including compatibility constraints. |
| FR-04 | Fetch missing dependencies and reuse cached copies across projects. |
| FR-05 | Provide a canonical `Module.scala` hardware entry point with a well-defined exported construction interface. |
| FR-06 | Provide a selectable `System.scala` entry point that defines the desired system and integrates the project's exported hardware module where appropriate. |
| FR-07 | Support standalone elaboration of the project module when its interface permits it. |
| FR-08 | Support adding components to a system and replacing internal components through supported host extension points. |
| FR-09 | Support project-local or independently distributed adapters between generators. |
| FR-10 | Generate RTL and invoke existing simulation and deployment backends. |
| FR-11 | Support multiple evaluation configurations without duplicating the hardware implementation. |
| FR-12 | Permit local overrides of remote generator sources for development. |
| FR-13 | Export multiple independently selectable host-specific configuration fragments and adapters from one hardware project. |
| FR-14 | Resolve one project-wide external dependency graph from explicit declarations and their transitive closure, independent of experiment or standalone selection; no inference of dependencies from system imports. External libraries may be compiled even when unused by the selected system; optional integration source selection is separate (FR-23). |
| FR-15 | Permit separately distributed generator wrappers for non-Chipyard and non-Chisel hardware, including explicit RTL production and integration contracts. |
| FR-16 | Distinguish whole-component integration from internal replacement; external wrappers need not support internal replacement. Internal replacement is supported only through available extension points; new staged RTL rewriting and binding infrastructure is out of scope. |
| FR-17 | Allow project code to use direct and resolved transitive dependencies. References outside the effective dependency graph and local/platform sources must fail compilation or validation, even when cached. Warnings for direct use of transitively supplied dependencies are optional and not required for v1. |
| FR-18 | Keep unused adapters and configuration fragments passive; elaborate only the hardware selected by the system, with no eager registration or construction side effects. |
| FR-19 | Support multiple `config/<experiment>/System.scala` entry points, selected at build invocation; compile only the selected configuration source set. |
| FR-20 | Support reproducible parameter sweeps and multiple experiments without copying the hardware implementation or manually modifying upstream generators. |
| FR-21 | Deferred beyond current scope: generalized wrapper-driven RTL substitution, intermediate stubs and post-elaboration replacement. No implementation requirement in v1. |
| FR-22 | Keep shared source caches immutable and build artifacts isolated. Record the identities of selected generators and integration recipes. Specialized replacement transformation/binding validation is deferred with FR-21. |
| FR-23 | Use import-driven selection of optional integration source units before compilation, starting from the selected experiment or standalone entry point and its active sources. Exclude unselected integration units even when their hosts are available. No manual adapter selection or disabling. A named import selects its integration unit even if it is not subsequently elaborated. The selector implementation remains to be validated (Q-19). |
| FR-24 | Support optional dependency aliases as distinct dependency instances, permitting multiple revisions of the same generator to be resolved, cached and exposed through distinguishable Scala namespaces within one project-wide graph. Separate experiments must be able to select revisions by imports in v1. Simultaneous instantiation in one system is a design goal, subject to a successful namespace-isolation and hardware-compatibility prototype; incompatible shared infrastructure is not required to coexist. |

### 3.2 Non-functional requirements

- Reproducibility: pin source revisions and toolchain versions; reject unresolved conflicts.
- Portability: support different project locations, shared caches, and offline cluster builds after prefetching.
- Maintainability: minimize upstream modifications and avoid a Chisel fork.
- Transparency: expose the resolved dependency graph, integration configuration, and generated workspace.
- Safety of builds: treat third-party Scala and sbt code as executable code; do not fetch or run arbitrary untrusted builds implicitly outside the selected trust policy.
- Performance: reuse source and compiled artifacts when inputs match; isolate concurrently running builds.

## 4. Proposed project structure

The tree below instantiates the workspace conventions for one BTB project. Package, host and experiment names belong to that example.

```text
my-awesome-btb/
├── build.sbt
├── project/
│   ├── build.properties
│   └── plugins.sbt
├── hardware.lock
├── src
|   ├── /main/scala/mybtb/
│   |   ├── Module.scala             # Canonical hardware entry point
│   |   ├── modules/
│   |   │   ├── MyBTB.scala
│   |   │   ├── TagArray.scala
│   |   │   └── TargetArray.scala
│   |   └── adapters/                # Host-independent adapters, if any
|   ├── /test/scala/mybtb/
├── integrations/                # Passive, isolated host-specific source sets
│   ├── boom/src/main/scala/
│   │   ├── BoomBTBAdapter.scala
│   │   └── BoomWithMyBTB.scala
│   ├── blackparrot/src/main/scala/
│   │   ├── BlackParrotBTBAdapter.scala
│   │   └── BlackParrotWithMyBTB.scala
│   └── rocket/src/main/scala/
│       ├── RocketBTBAdapter.scala
│       └── RocketWithMyBTB.scala
├── config/
│   ├── boom-baseline/System.scala
│   ├── boom-custom-btb/System.scala
│   ├── boom-large-btb/System.scala
│   └── blackparrot-custom-btb/System.scala
├── README.md
└── .gitignore
```

One canonical hardware entry point is shared by all experiments. Each `config/<experiment>/System.scala` is an independently selectable system definition, not an integration library. Only the chosen configuration source set is compiled for a system build; unselected configurations may even use the same fully qualified entry-point class name without collisions if they are compiled in isolation. Shared configuration fragments may live in a common source set and consume the same project-wide dependencies. Standalone builds need no system configuration.

A future multi-export mechanism could expose secondary hardware generators without abandoning the canonical default module entry point.

## 5. Dependency specification: `build.sbt`

Extend sbt with a hardware dependency management plugin and, if required, a bootstrap resolver. Do not create a separate user-facing dependency manifest that duplicates `build.sbt`.

Illustrative API:

```scala
enablePlugins(ChipyardPlugin)

name := "MyAwesomeBTB"
version := "0.1.0"

chipyardVersion := "<pinned-release>"

hardwareDependencies ++= Seq(
  Generator.chipyard("boom"),
  Generator.git(
    name = "memory-controller",
    url  = "https://github.com/example/memory.git",
    ref  = "<commit>"
  ),
  Generator.local(
    name = "custom-cache",
    path = file("../my-cache")
  )
)

// All other external dependencies needed by any experiment belong here.
// No experiment registration or experiment-to-dependency mapping.
```

The hardware build manager locates the canonical module and selected system entry points by convention or separate entry-point metadata, not by experiment declarations in `build.sbt`. The exact entry-point symbol contract remains open. System selection is not a source-dependency discovery mechanism.

| Proposed source type | Purpose |
|---|---|
| `Generator.chipyard()` | Select a generator from a pinned Chipyard release. |
| `Generator.git()` | Fetch an independent or modified generator from Git. |
| `Generator.local()` | Use a local development checkout. |
| `Generator.maven()` | Consume a published Scala hardware library. |
| `Generator.archive()` | Consume a versioned source archive (later). |

### 5.1 Dependency graph

Requesting BOOM should resolve its required dependencies, such as the compatible Rocket Chip and Chisel versions. Chipyard currently supplies some of the integration metadata in its own build definition, so a repository URL alone is not sufficient for every legacy generator.

Use the selected Chipyard revision as the initial compatibility baseline. Supply versioned integration recipes for legacy generators that specify their source roots, resources, project dependencies, compiler settings, and any required build steps. Projects that adopt the new plugin may publish their own dependency metadata instead.

Shared infrastructure dependencies should resolve to a common compatible version where required. Explicitly aliased generator instances may coexist at distinct revisions (Section 5.4); do not collapse them into a single version. Aliasing a generator does not automatically duplicate its transitive dependency tree. If two generators demand incompatible versions of Rocket Chip, Chisel or other shared integration infrastructure, fail with an actionable explanation rather than silently selecting a version or patching source code.

### 5.2 Authoritative, project-wide dependency declarations

`build.sbt` is the sole user-facing source of truth for the roots of the external dependency graph. Project code, integration code, and the selected system may use dependencies available through either direct declarations or their resolved transitive closure, in addition to the selected platform/toolchain baseline. Local source code in the active project needs no external declaration. An external local checkout must enter the dependency graph through a local dependency declaration (directly or transitively).

- Resolve the declared dependencies and their necessary transitive closure. A dependency's own requirements are resolved from its declared metadata or pinned legacy recipe. Users need not repeat a transitive dependency in their own `build.sbt`, even if their own code directly uses it, provided it is exposed on the relevant compile classpath under the normal dependency visibility rules.
- Allow direct use of dependencies supplied transitively through the effective graph. Do not implement strict direct-dependency enforcement for v1; use ordinary sbt/Scala dependency visibility and missing-symbol diagnostics. A future non-blocking diagnostic may recommend declaring directly used transitive dependencies explicitly for maintainability, but builds must not fail solely for this reason.
- Do not inspect `System.scala` to infer new dependencies, fetch missing repositories automatically in response to imports, or register every known generator. A missing reference must fail compilation or pre-compilation validation with an actionable diagnostic. The lockfile/cache cannot grant availability beyond the current resolved dependency graph.
- Declare the union of external dependencies required by the repository, including all its experiments, integrations, tests and standalone use cases as appropriate. An explicitly declared but unused dependency may still be fetched or compiled; an unused declaration is not automatically pruned by examining the hardware graph. Unused-dependency diagnostics may be added later.

All configurations consume the same dependency graph. `build.sbt` does not enumerate experiments, map them to dependencies, or change dependency resolution based on the selected configuration. This is analogous to a project-wide npm or Python package manifest. Ordinary library dependency relationships and visibility rules are still supported; they are not experiment-specific declarations.

For example, a repository evaluating a BTB on BOOM and BlackParrot declares both hosts once:

```scala
hardwareDependencies ++= Seq(
  Generator.chipyard("boom"),
  Generator.git(
    name = "blackparrot-wrapper",
    url  = "<repository>",
    ref  = "<commit>"
  )
)
```

Resolving dependencies needs no experiment selection. Changing `config/<name>/System.scala` changes the selected source and hardware composition, not the external dependency graph. All experiments share one lockfile and one resolved graph, which may contain explicitly aliased revisions of the same generator. Experiments select those revisions through imports, not through changes to dependency declarations. Incompatible shared infrastructure remains a project-wide resolution error in the initial design; aliases do not introduce experiment-specific dependency resolution.

### 5.3 Adapter source isolation and passive elaboration

Host-specific adapter code may require host classes even if it never generates hardware. Scala compiles source sets before elaboration: leaving a BlackParrot adapter in an active source set when BlackParrot is unavailable will produce compilation errors. Isolate host-specific integrations in separate source sets/subprojects, with any source-unit prerequisite metadata kept at the integration level and checked against the already resolved project-wide graph. Such metadata is not another dependency declaration and cannot fetch missing generators. Only selected build source sets enter the compilation graph; do not compile every repository-local Scala file indiscriminately.

Host availability is a prerequisite, not a selection signal. A BOOM baseline should not have to compile the custom BOOM adapter merely because BOOM is available. Distinguish (1) external dependency resolution, (2) integration source selection, (3) Scala compilation, and (4) hardware elaboration. Dead-code elimination is too late to solve missing symbols; incremental compilation is not a substitute for selecting the initial source set.

**Desired user experience:** the selected `System.scala` references the integration normally, e.g. `import mybtb.integrations.boom.BoomWithMyBTB`. No separate adapter list is necessary. The build manager may select already-local or already-resolved integration source units by their references, but it must never infer additional external dependency roots from them. A selected adapter whose prerequisites are absent from the effective graph fails; an unselected adapter must not cause a missing-dependency error.

**Selected design for v1 (v0.10): import-driven source selection.** Ordinary named Scala imports of optional integration packages are the positive selection signal. The build manager performs source selection before ordinary sbt/Scala compilation; the developer does not maintain a separate adapter list or change `build.sbt` to select an integration. The dependency graph is resolved independently and is never extended by source selection.

For example:

```scala
// config/boom-custom-btb/System.scala
package mybtb

import org.chipsalliance.cde.config.Config
import mybtb.integrations.boom.BoomWithMyBTB

class System extends Config(
  new BoomWithMyBTB ++
  new chipyard.MediumBoomV4Config
)
```

The named import selects the BOOM integration source unit, containing `BoomWithMyBTB`, `BoomBTBAdapter`, and any helpers belonging to that unit. A baseline without this import (or another active source importing this integration) excludes the unit. The BlackParrot integration remains excluded even if BlackParrot is a declared dependency. This is selection at integration-unit granularity, not dead-code elimination or general semantic reachability analysis.

Proposed implementation contract (details subject to prototype validation):

1. Discover optional integration units in the local project and in conforming source dependencies already present in the resolved graph. Use a stable package-to-directory convention (e.g. `mybtb.integrations.boom` maps to the BOOM integration source root); separate metadata is only needed if convention alone is insufficient. Do not typecheck or compile optional units to build this index.
2. Start with the selected system configuration and ordinary active project source sets (or standalone entry point and its active sources). Parse their Scala imports using a syntax-aware parser, rather than scanning comments or strings with a regular expression. Unselected experiment files and optional integration bodies are not initial roots.
3. For each supported named integration import, add the owning unit once. Parse imports in newly selected units and iterate to a fixed point, including inter-integration references. The selector must also account for imports in active helper files, not only in `System.scala`. Report duplicate/ambiguous unit mappings explicitly. All ordinary source files within an active source unit are compiled normally.
4. Supply only the selected integration source units and normal active sources to sbt/Scala. Ordinary dependency imports use the resolved classpath; if a selected unit references an unavailable external dependency, the build fails rather than expanding the dependency graph. Isolate outputs/classpaths so excluded integrations cannot leak in from previous builds.
5. Report which integration units were selected, by which source import, and which were excluded. Elaborate only the hardware actually instantiated by the selected system.

Initial import convention: use explicit named imports across optional integration-unit boundaries, including when one integration uses another. A named import is a selection request even if unused by later Scala code. Same-unit references need no special handling. Grouped or aliased named imports are reasonable syntax extensions to validate in the prototype. Wildcard imports across integration boundaries, fully qualified references without a named import, implicit-only discovery and reflective selection are not promised in v1; require a named import when they would otherwise be the sole selection signal. Emit actionable diagnostics for unsupported selection forms where detectable; otherwise normal compilation fails. No custom Scala syntax or general Scala symbol resolver is required by this design.

**Implementation alternatives:** Native Scala 2 `-sourcepath` and late source loading may be investigated as an internal implementation optimization if they preserve the same import-driven user-facing contract and work with Zinc and Chisel plugins. Explicit positive source-unit selection outside `build.sbt` is an implementation fallback/debugging option only if the prototype encounters blockers; it is not the normal developer workflow or an equally preferred design. Compiling every adapter with an available host does not satisfy FR-23.

Isolation is at source-file or integration-subproject granularity, not an arbitrary Scala method or runtime hardware-instance granularity. A selected source file can contain definitions not elaborated. Declared external libraries may compile even if unused; FR-23 concerns optional integration units, not whole-program dead-code analysis. Report selected/excluded integration units and the reason for selection in a build plan (a proposed `hardwarePlan` task or verbose build log). Key compilation artifacts by the selected source units and effective classpath; never reuse stale adapter classes from a previously selected experiment. Test each integration explicitly in CI against the project-wide dependency graph.

Even a compiled adapter generates no hardware until selected by the system/host factory. Do not eagerly instantiate modules in Scala singleton initialization, adapter registration, or global lists. Config fragments configure factories; calling a factory during elaboration constructs hardware. For external RTL wrappers, defer variant-specific generation and inclusion until selected where technically possible; compilation prerequisites may still require declared artifacts earlier.

### 5.4 Dependency aliases and multi-version generators

A research project should be able to compare two revisions of a generator without maintaining separate repositories, switching Git checkouts or changing `build.sbt` between experiments. An optional `alias` identifies a distinct dependency instance. The generator name is the default alias if no alias is supplied. Aliases must be unambiguous within the relevant project dependency graph; reject conflicting declarations rather than silently merging them. An alias is not merely a source-directory suffix or an ordinary Scala import rename.

Illustrative declaration (both revisions belong to the same project-wide graph):

```scala
hardwareDependencies ++= Seq(
  Generator.git(
    name  = "boom",
    url   = "https://github.com/riscv-boom/riscv-boom.git",
    ref   = "h1",
    alias = "boom_baseline"
  ),
  Generator.git(
    name  = "boom",
    url   = "https://github.com/riscv-boom/riscv-boom.git",
    ref   = "h2",
    alias = "boom_experimental"
  )
)
```

Here `h1` and `h2` stand for pinned Git commits. The resolver must retain both graph nodes rather than applying ordinary version eviction to them. Each node has its own dependency edges and build identity; the source cache still identifies the original repository and commit, not the alias. A generated workspace may expose `generators/boom_baseline/` and `generators/boom_experimental/`, with separate compilation outputs. If both aliases refer to the same source revision, they may reuse the immutable source cache while keeping distinguishable build identities where necessary.

**Three levels of isolation:**

| Level | Contract |
|---|---|
| Source isolation | Distinct revisions are simultaneously available in separate source/workspace directories; cached originals remain immutable. |
| Scala namespace isolation | Aliased versions expose distinguishable fully qualified Scala names or an equivalent typed import-facing interface. Separate directories or sbt project IDs alone do not distinguish JVM classes with identical fully qualified names. |
| Hardware isolation | When both variants are instantiated in one SoC, their configuration keys, instance identities, module names and RTL resources must remain unambiguous, and their interfaces must be compatible with the shared platform. |

The desired import-facing namespace is illustrated by `boom_baseline.v4.common.BoomCoreParams` and `boom_experimental.v4.common.BoomCoreParams`. Exact namespace mapping and alias syntax are provisional. Ordinary unaliased dependencies should retain their existing import paths where possible. An ordinary Scala import rename alone cannot resolve two versions of the same class on a classpath.

**Implementation candidates.** For separate experiments, isolated compilation outputs/classpaths may be sufficient internally, provided the user-facing alias/import contract is preserved and the project-wide resolved graph is unchanged. For simultaneous use in one Scala compilation and elaboration, investigate syntax-aware source package relocation first: rewrite generator-owned package declarations, imports and qualified references in a derived workspace (e.g. `boom.v4.common` to `boom_baseline.v4.common`), without changing shared `chisel3` or platform packages. This is a proposed build-time namespace transformation, not the deferred post-elaboration RTL substitution mechanism. It must also account for package-private qualifiers, package objects, external integrations, compiler/plugin-generated names, reflection and resource lookups where relevant. Do not use unrestricted text replacement. An alias-aware legacy integration recipe may be necessary. Bytecode relocation (shading) is an alternative to investigate; separate classloaders are a more complex alternative and not the preferred initial approach. None is assumed to work for BOOM before validation.

**Shared dependencies.** Aliases isolate the selected generator, not automatically every dependency below it. Both BOOM instances should share a compatible Rocket Chip, Chisel, Diplomacy, CDE and Chipyard platform wherever possible. Edges from each alias must resolve explicitly to the correct dependency node; do not infer new roots from imports. Reject ambiguous or incompatible shared dependencies with actionable diagnostics. Aliasing incompatible transitive dependencies is future work; multiple incompatible Chisel toolchains in one SoC are outside current scope. Compatibility is not guaranteed by matching names or revision numbers alone.

**Scope.** The v1 target includes alias declarations, multi-revision resolution and caching, distinct import-facing identities, and independently selectable revision experiments without modifying `build.sbt` or the lockfile. A same-system two-revision prototype is a follow-on milestone: support simultaneous instantiation where Scala namespaces, shared infrastructure, configuration and emitted RTL are compatible. Do not claim arbitrary multi-version composition before those checks succeed. The existing import-driven selection of optional integration source units (Section 5.3) remains separate from dependency resolution; importing an alias or alias-specific integration never fetches an undeclared generator.

## 6. Hardware entry point: `Module.scala`

### 6.1 Purpose and contract

`Module.scala` plays the role of `main.c` for a hardware generator project. It identifies what this repository exports as its primary hardware contribution. It is distinct from the top-level module of the complete SoC, which is determined by `System.scala` and its backend.

Proposed initial contract:

1. A conforming hardware project contains a canonical `Module.scala` file.
2. This file exposes a public, typed hardware construction interface, provisionally an object named `Module`.
3. The interface identifies the project module's parameter type and concrete hardware type through ordinary Scala types.
4. The entry point constructs a fresh hardware instance for each invocation, during elaboration, rather than creating a global hardware singleton.
5. The hardware can be instantiated by the system, adapters, tests, or a standalone elaborator.
6. The implementation can be in `Module.scala` itself or delegated to other source files.

Illustrative implementation (ordinary Scala/Chisel, except that the entry-point convention is new):

```scala
package mybtb

import chisel3.{Module => ChiselModule, _}

final case class BTBParams(
  entries: Int = 1024,
  ways: Int = 4,
  addressWidth: Int = 64
)

final class MyBTB(p: BTBParams) extends ChiselModule {
  val io = IO(new Bundle {
    val lookupPC = Input(UInt(p.addressWidth.W))
    val hit      = Output(Bool())
    val target   = Output(UInt(p.addressWidth.W))
  })

  // Actual BTB implementation omitted.
  io.hit    := false.B
  io.target := 0.U
}

object Module {
  def create(p: BTBParams = BTBParams()): MyBTB =
    new MyBTB(p)
}
```

`Module.create` is a construction factory, not an elaboration command. Inside an enclosing Chisel module, use `chisel3.Module(mybtb.Module.create(params))`; for standalone generation, pass a suitable generator expression to the Chisel stage instead. The exact stage API is tied to the selected Chisel version. The factory must not be invoked during sbt dependency resolution or arbitrary Scala object initialization outside elaboration.

Naming the exported object `Module` introduces a collision with Chisel's own `Module` symbol. Qualified names or import aliases handle this, as shown above. An alternative is to keep the mandatory filename but choose a different standard symbol, such as `HardwareEntry`. This should be decided explicitly rather than conflating the project entry point with Chisel's module-construction primitive.

### 6.2 Why distinguish the module from the system?

The same BTB implementation can be integrated into BOOM, adapted for another CPU, or synthesized and tested alone. The hardware contribution does not need to know which system uses it. Similarly, the same accelerator can be integrated with different CPU, bus, and memory configurations.

`Module.scala` identifies the reusable hardware. `System.scala` describes the context in which that hardware is evaluated.

## 7. System composition: `System.scala`

The selected `config/<experiment>/System.scala` is the integration entry point for one experiment. It identifies the chosen host generator, configures system parameters, and selects the project's module for integration when desired. It declares only the intended system, never all available adapters or dependencies. A baseline system may omit the project module entirely. It need not directly instantiate the module in its own Scala constructor; it may supply a factory or configuration fragment that will instantiate it at the correct elaboration point.

Two distinct use cases must be supported.

### 7.1 Adding hardware to a system

For example, integrating an accelerator into a system with BOOM and a memory controller. A future composition API could look like:

```scala
object System extends HardwareSystem {
  val cpu = add(BoomCore(config = "Medium"))
  val accelerator = add(AcceleratorEndpoint(
    create = () => myaccelerator.Module.create()
  ))
  val bus = add(TileLinkInterconnect())
  val memory = add(MemoryController())

  connect(cpu.memory, bus)
  connect(accelerator.memory, bus)
  connect(memory, bus)
}
```

These are proposed descriptors, not live Chisel modules. The composition layer would translate them into the appropriate Chipyard configuration, Diplomacy topology, and elaboration-time constructors. The adapter must specify the interface, address map, and any required clock/reset behavior.

### 7.2 Replacing hardware within a generator

For example, replacing BOOM's BTB with a custom implementation:

```scala
object System extends HardwareSystem {
  val cpu = add(BoomCore(config = "Medium"))

  cpu.frontend.btb.replaceWith(
    BoomBTBAdapter(
      create = () => mybtb.Module.create(
        BTBParams(entries = 2048, ways = 4)
      )
    )
  )
}
```

The above syntax describes the desired end state. It does not imply that arbitrary internal modules can already be replaced or that the framework can rewrite the hardware hierarchy automatically.

For native Chisel construction, the host must expose an extension point that permits a custom constructor or factory. For external RTL, whole-generator integration is sufficient; a wrapper may expose existing extension points or depend on an explicitly modified upstream version. Generalized RTL transformations are deferred (Section 9.2). For BOOM, the configurable predictor construction mechanisms are a candidate; their applicability to a particular BTB and release needs verification. A custom bank may need to preserve the remainder of the predictor pipeline and its interfaces. If a suitable extension point is absent, a small upstream change or a pinned modified host is necessary.

### 7.3 Minimum viable system entry point

Initially, avoid introducing a new DSL. Use ordinary Chipyard configuration classes as `System.scala` in the selected system source set (not in the standalone module compilation unit if it references an optional host):

```scala
package mybtb

import org.chipsalliance.cde.config.Config
import mybtb.integrations.boom.BoomWithMyBTB

class System extends Config(
  new BoomWithMyBTB ++
  new chipyard.MediumBoomV4Config
)
```

`BoomWithMyBTB` is a proposed project-defined configuration fragment. It selects the host's predictor construction function and supplies an adapter that calls the project's `Module.create` at elaboration time. The example config class name and the host interfaces must be checked against the chosen pinned versions.

This preserves the distinction between the hardware entry point and the system entry point without requiring a new system composition language.

### 7.4 Multiple host-specific configuration fragments

A project may export any number of named, parameterized configuration fragments. Each fragment selects a host-specific integration, while the canonical `Module.scala` and its hardware implementation are shared. For example:

```scala
// In config/boom-custom-btb/System.scala:
import mybtb.integrations.boom.BoomWithMyBTB

class System extends Config(
  new BoomWithMyBTB(BTBParams(entries = 2048)) ++
  new MediumBoomConfig
)

// Future illustration only, if the wrapper exposes a compatible replacement point:
// In config/blackparrot-custom-btb/System.scala (separate build):
import mybtb.integrations.blackparrot.BlackParrotWithMyBTB

class System extends Config(
  new BlackParrotWithMyBTB(BTBParams(entries = 2048)) ++
  new BlackParrotConfig
)
```

These names and constructors are illustrative. Chipyard's existing CDE configuration fragments provide a suitable initial mechanism; each host-specific fragment maps the common parameters onto its host's actual extension point. The usual Chipyard `With...` naming convention (e.g. `WithMyBTBInBoom`) is also possible; the final naming convention is not yet fixed.

| Export | Responsibility |
|---|---|
| `Module.scala` | Canonical reusable BTB construction interface. |
| `BoomBTBAdapter` | Translate BOOM predictor semantics to the common BTB interface. |
| `BoomWithMyBTB` | Select the BOOM adapter through a supported host configuration key or constructor. |
| `BlackParrotBTBAdapter` | Translate BlackParrot's RTL-facing predictor interface to the same BTB. |
| `BlackParrotWithMyBTB` | Select a BlackParrot build variant with an available predictor extension point (hypothetical; not a current deliverable). |
| `config/<experiment>/System.scala` | Select only the host, integration, evaluation settings and deployment context used by this experiment. |

The fragment is not the adapter, and the adapter is not the implementation. Integration fragments may be provided by either upstream project or by an independent third party. Host versions and supported replacement contracts must be recorded. For heterogeneous systems, fragments should identify their target instances/harts explicitly rather than unintentionally change every instance of a host.

### 7.5 Experiment selection and design-space exploration

Select the experiment in the hardware build manager before source selection and compilation. Dependency resolution is independent and can happen before or after experiment selection. For example (illustrative build-manager CLI, not implemented):

```sh
hardware elaborate --config boom-baseline
hardware simulate --config boom-custom-btb
hardware simulate --config boom-large-btb
```

Each configuration may be a complete system class or compose shared local configuration fragments. A baseline can use only `new MediumBoomConfig`; a custom configuration can prepend `new BoomWithMyBTB(BTBParams(entries = 2048))`; another can change the entry count to 4096. None needs to list or disable adapters not used. Different systems may target different hosts available in the single project-wide resolved graph. Identical Scala entry-point names are permitted in isolated source sets; the selected class is passed to Chipyard's existing elaboration flow.

Initially, explicit Scala configurations suffice for design-space exploration. Later, a sweep runner may select named experiments and typed parameter presets, record effective parameters and commands, and produce independent run manifests and output directories. It must not expand or bypass the project-wide resolved dependency graph. The precise mechanism for sharing common settings and passing sweep parameters remains open.

### 7.6 Comparing aliased generator revisions

The same repository can contain `config/boom-baseline/System.scala`, `config/boom-experimental/System.scala`, and, once validated, `config/boom-combined/System.scala`. The selected configuration imports the desired dependency instance from the graph established in `build.sbt` (Section 5.4).

```scala
// config/boom-baseline/System.scala
import boom_baseline.config.WithBoomCore

class System extends Config(
  new WithBoomCore ++
  new BaseConfig
)
```

```scala
// config/boom-experimental/System.scala
import boom_experimental.config.WithBoomCore

class System extends Config(
  new WithBoomCore ++
  new BaseConfig
)
```

A possible combined experiment uses both namespaces, with ordinary Scala import renaming to distinguish their local names:

```scala
// config/boom-combined/System.scala
import boom_baseline.config.{WithBoomCore => WithBaselineCore}
import boom_experimental.config.{WithBoomCore => WithExperimentalCore}

class System extends Config(
  new WithBaselineCore(hartId = 0) ++
  new WithExperimentalCore(hartId = 1) ++
  new BaseConfig
)
```

These are illustrative fragments and constructors, not existing BOOM APIs. The integration must supply or adapt the actual configuration fragments, compose tiles without overwriting each other's settings, and use distinct hart IDs and other necessary instance identifiers. Both revisions must integrate with the common platform. Alias-aware adapter imports must select the intended host revision and the appropriate integration unit; no manual adapter enable/disable list or experiment-specific dependency declarations are introduced. A named aliased import that crosses an optional integration boundary must be recognized by the source selector if used in the supported examples. Validate this syntax as part of the selector prototype.

Scala-level isolation does not establish RTL-level correctness: verify emitted module and external RTL resource identities, parameter/configuration scoping, interrupts, memory/coherence interfaces and functional behavior. Separate-experiment support is the v1 requirement; the combined example is the target of a compatibility prototype.

## 8. Component replacement and Chisel changes

No new Chisel primitive is required for the initial design. Ordinary Scala constructor injection and Chipyard's existing configuration mechanisms can select implementations before hardware is elaborated.

Conceptual example for a host designed with replaceability in mind:

```scala
class CPU(createBTB: () => AbstractBTB) extends chisel3.Module {
  val btb = chisel3.Module(createBTB())
  // Rest of CPU implementation.
}
```

A future library could expose typed replacement slots with a default constructor and a well-defined interface contract. A slot is a Scala library abstraction, not a language primitive. Prefer type-checked identifiers and factories over untyped hierarchy strings.

| Mechanism | Chisel changes? | Host changes? |
|---|---|---|
| Constructor injection | No | Only if not supported already |
| Chipyard configuration/factory functions | No | Only if not supported already |
| Typed replacement slots | No | Host must expose slots or mappings |
| Existing wrapper-defined extension point | No | Wrapper or explicitly modified upstream source must expose the point |
| Staged RTL substitution / post-elaboration rewriting | Not assessed | Deferred to future work |
| Automatic replacement of arbitrary internal hardware | Undetermined | More invasive tooling; explicitly out of scope |

Even a type-compatible replacement may be functionally incompatible because of latency, protocol, speculation, reset, or ordering requirements. Type compatibility alone is insufficient.

## 9. Adapter architecture

Adapters are first-class integration components and may be supplied by the research project, host generator, or a separate dependency.

```text
Host interface  <-->  Adapter  <-->  Project Module.create(...)
                      |
                      +-- signals, timing, protocol, semantics
```

Examples include AXI-to-TileLink adapters, width conversions, timing/handshake adaptation, BOOM predictor bank wrappers, and Chisel wrappers around Verilog IP. Adapters must document their assumptions and be tested independently. A protocol conversion cannot necessarily compensate for a microarchitectural latency change or different speculation semantics.

Keep the implementation module independent of the host where practical. Prefer an adapter that constructs the canonical module via its public entry point over one that depends directly on its internal implementation classes.

### 9.1 Generator wrappers for external projects

A generator wrapper is a separately versioned project that presents an existing non-conforming hardware repository through the framework's canonical hardware entry point and integration contracts. The original upstream repository remains a dependency, not a required fork or port to Chisel. This is distinct from a component adapter, which connects two particular hardware interfaces. A wrapper may contain several adapters and configuration fragments.

Existing Chipyard integration of SystemVerilog CVA6 and Ibex demonstrates the feasibility of whole-core wrapping. Chipyard's BlackBox facilities and custom tile integration are initial building blocks; they do not establish that any arbitrary external project or internal replacement will work automatically.

Example wrapper repository:

```text
blackparrot-generator/
├── build.sbt                    # Pinned upstream and build recipe
├── src/main/scala/...
│   ├── Module.scala             # Canonical wrapper entry point
│   ├── BlackParrotBlackBox.scala
│   ├── BlackParrotTile.scala
│   └── WithBlackParrot.scala
├── config/default/System.scala # Optional wrapper evaluation system
├── rtl/                         # Wrapper-owned SV glue, if required
├── recipes/                     # Source discovery and external build steps
└── tests/
```

The wrapper must describe upstream source identity, build tools and inputs, ordered RTL file lists, include paths, macros, packages, top module, parameter mapping, port definitions, clock/reset behavior, and backend constraints. It exposes a typed Chisel construction interface or a Diplomacy tile factory, while the original RTL remains in its own language. CPU wrappers additionally need memory and coherence integration, interrupts, hart IDs, boot/reset behavior and any relevant debug or device-tree support. Incompatible coherence semantics cannot be solved simply by translating signal names.

| Upstream form | Integration strategy |
|---|---|
| Existing Chisel generator without Chipyard glue | Compile source and provide a wrapper/factory and required Diplomacy integration. |
| Verilog/SystemVerilog IP | Reference RTL through Chisel BlackBox/ExtModule facilities and provide interface glue. |
| SpinalHDL, Amaranth or other RTL generator | Run the upstream generation tool in an isolated build stage, then wrap the emitted RTL. |
| VHDL | Use a compatible mixed-language backend or a verified translation flow; not guaranteed across backends. |

The wrapper's recipe may depend on tools outside sbt; the build manager orchestrates these tools before the consumer stage that needs their outputs. For the initial implementation, external RTL must be available through ordinary wrapper generation and integration steps; parameter-dependent replacement staging is deferred. Cache generated RTL using the generator revision, resolved parameters, tool versions, dependencies and recipe inputs. Preserve RTL file order and artifact manifests. Each wrapper declares supported simulation/synthesis backends; FireSim and other transforms may impose additional limitations on opaque RTL.

### 9.2 Scope of internal replacement in external wrappers

Whole-generator integration is sufficient for a non-Chisel wrapper. A wrapper may expose an existing upstream extension point or use an explicitly selected modified upstream revision, but the framework does not need to discover, carve out or replace internal RTL. A request for an unsupported replacement must fail rather than silently modify upstream hardware.

**Deferred to future work:** wrapper-driven source/RTL transformation pipelines, generated replacement stubs, post-elaboration RTL substitution, and generic Chipyard binding of replacement implementations. These are not requirements, acceptance tests or implementation milestones of the current project. If revisited, the wrapper should own the host-specific transformation and replacement contract; the orchestration API and execution order are intentionally left unspecified.

### 9.3 BlackParrot case study (illustrative)

A BlackParrot wrapper can expose a complete SystemVerilog core without internal replacement support. `BlackParrotWithMyBTB` remains a hypothetical example of a host-specific configuration fragment, conditional on a suitable extension point being available. Neither a custom BlackParrot BTB nor any new RTL replacement infrastructure is required for whole-core wrapping. No BlackParrot wrapper or replacement hook has been verified in this design iteration.

## 10. Build architecture and execution flow

```text
build.sbt / hardware.lock (project-wide)      config/<experiment>/System.scala
                |                                         |
                v                                         |
     Dependency resolver + compatibility recipes          |
                |                                         |
                v                                         v
     Pinned source cache + dependency graph ---> Hardware build manager
                                                  source selection
                                                         |
                                                         v
                                       sbt / Scala compilation of selected sources
                                                         |
                                                         v
                                      Selected system + invoked integration factories
                                                         |
                                                         v
                                      Module.scala -> Chisel / CIRCT elaboration
                                                         |
                                                         v
                                        RTL assembly -> simulation / FPGA / synthesis
```

Illustrative hardware build-manager commands (not implemented):

```sh
hardware resolve
hardware elaborate --config boom-custom-btb
hardware simulate --config boom-custom-btb
```

1. Read the authoritative project-wide `build.sbt` declarations and lockfile; resolving dependencies does not require an experiment name.
2. Resolve the Chipyard version, declared sources, transitive dependencies, and compatibility constraints for the whole repository.
3. Fetch missing repositories and prepare the reusable dependency workspace. The selected experiment does not alter the dependency graph.
4. The hardware build manager selects the experiment (or a standalone entry point), identifies needed integration source units from named imports in the active source closure (Section 5.3), and prepares any required alias-specific namespaces, source units and compilation outputs from the already resolved graph (Section 5.4), then invokes sbt/Scala compilation. The manager may generate a temporary compilation graph; the user's `build.sbt` does not enumerate configurations. Declared external dependencies may compile even when unused. Fail on missing dependencies; do not fetch them based on system code.
5. Elaborate only the selected system and invoked integration factories, constructing the project's module through its public entry point if selected.
6. Generate RTL and assemble any ordinary external RTL resources supplied by selected wrappers, then delegate to the existing backend. No new post-elaboration component replacement stage is required.

**Bootstrap constraint:** if dependency resolution changes sbt's project graph, it must happen before that graph is finalized, or via a controlled reload/second build. A normal sbt task cannot assume it can add arbitrary new projects to an already-loaded build. The first implementation may use a launcher or a separate bootstrap build, even if `build.sbt` remains the user-facing source of truth. `System.scala` cannot be responsible for fetching compile-time dependencies because it must already be compiled before elaboration.

## 11. Caching and reproducibility

Use immutable, commit-keyed source caches and isolated build workspaces. Local dependencies remain editable, and should not be represented as immutable remote sources.

```text
~/.cache/hardware-generators/
├── sources/<repository-identity>/<commit>/
├── artifacts/<build-fingerprint>/
└── toolchains/<toolchain-fingerprint>/

<project>/target/hardware/<build-fingerprint>/
```

A source-cache key uses canonical repository identity and resolved revision, independent of the project alias. A compilation/artifact key must account for canonical source identity, revision, relevant dependency revisions and edges, build settings, toolchain versions, integration recipes, relevant local source content, and, where applicable, alias namespace and namespace-transformation recipe/version. Alias-specific output directories or equivalent isolated artifacts prevent stale classes from one revision being reused for another. Avoid sharing writable source trees or mutable output directories across simultaneous cluster jobs. Support offline builds using a complete prefetched cache.

Illustrative lockfile (format not yet decided):

```toml
[chipyard]
version = "<release>"
commit = "<resolved-hash>"

[generators.boom]
repository = "https://github.com/riscv-boom/riscv-boom"
commit = "<resolved-hash>"

[generators.rocket-chip]
repository = "https://github.com/chipsalliance/rocket-chip"
commit = "<resolved-hash>"

[toolchain]
scala = "<resolved-version>"
chisel = "<resolved-version>"
circt = "<resolved-version>"
```

For multiple aliased revisions, the generator entries are keyed by alias rather than a single canonical generator name (illustrative alternative to the unaliased `generators.boom` entry above):

```toml
[generators.boom_baseline]
name = "boom"
repository = "https://github.com/riscv-boom/riscv-boom"
commit = "h1"

[generators.boom_experimental]
name = "boom"
repository = "https://github.com/riscv-boom/riscv-boom"
commit = "h2"
```

Record alias-to-source and alias-to-namespace mappings, per-instance dependency edges, and the namespace transformation identity if used. The run manifest additionally identifies the aliases actually instantiated and maps them to their revisions and hardware instances. Switching experiments must not change the resolved graph or lockfile merely to choose a different alias.

The real lockfile also needs dependency edges, submodule revisions where relevant, integration recipe identities, the project-wide resolved dependency graph, external RTL generation inputs and artifact manifests, and the tools required by selected backends. Local dependencies need explicit reproducibility metadata (e.g. Git revision and dirty status or a content hash); a path alone does not reproduce their content. A lockfile entry must not make a dependency outside the effective resolved graph available. Record the selected experiment, configuration source revision/content, effective parameters, project-wide lockfile/graph fingerprint, selected source units, and backend in each run manifest. Keep compiled artifacts keyed by compilation inputs and RTL/run outputs keyed additionally by the selected system and parameters, so experiments can reuse safe build artifacts without overwriting each other. Include selected native extension points, instance identities, wrapper recipe content, generator inputs and relevant tool versions in artifact fingerprints and run manifests.

## 12. Implementation boundaries

| Layer/repository | Responsibility | Initial approach |
|---|---|---|
| Hardware build manager (new) | Source fetching, resolution, caching, lockfiles, project graph | sbt plugin and possible bootstrap launcher |
| Hardware composition library (new, later) | System descriptors, typed slots, adapter contracts | Ordinary Scala library; avoid a DSL for MVP |
| Chipyard integration | Build definitions, SoC configuration, harnesses, backend invocation | Generate an isolated integration workspace from pinned upstream sources |
| BOOM / Rocket Chip / other hosts | Internal construction points and interfaces | Use existing extension points; modify only where necessary |
| Chisel / CIRCT | Elaboration and RTL generation | Unmodified |
| Research project | Canonical hardware and system entry points, selectable integration fragments and adapters | Independent repository |
| External generator wrapper | Present non-conforming upstream hardware as a generator; own ordinary RTL generation and host integration; optionally expose available extension points | Independently distributed wrapper repository |

Initial approach: keep the upstream Chipyard source revision unchanged in the cache and generate a disposable integration workspace with any necessary build definitions or overlays. This is not a claim that the unmodified Chipyard build already accepts the external project. Its current directory and build assumptions must be accommodated by the generated workspace or later upstream integration changes.

Long term: native external-project support in Chipyard could eliminate much of the generated integration machinery. Whether the plugin and composition library live in the same repository is still open.

## 13. Implementation roadmap and acceptance criteria

| Phase | Deliverable | Acceptance criterion |
|---|---|---|
| 1 | External generator resolution | Build a local and a Git-sourced generator without manually editing the upstream Chipyard checkout; second build reuses the cache. |
| 2 | Canonical entry points, selectable system configurations and import-driven source selection | Elaborate a standalone module, baseline BOOM, and custom BOOM configurations from the same repository; selecting one does not compile the other configuration source sets or unimported integration units. References outside the resolved dependency graph fail even with a warm cache; visible transitive references compile. |
| 2b | Multi-version generator dependencies (v1) | Declare two aliased BOOM revisions sharing a compatible platform, cache both and select each by imports in separate experiments without changing the project-wide graph or manually switching revisions. |
| 3 | Internal component replacement | Integrate a custom BTB through a verified BOOM extension point; run a functional simulation and compare against the default predictor configuration. |
| 4 | Selectable host integrations and external wrappers | Select different host-specific fragments from the same project-wide dependency graph without fetching hosts outside it or elaborating unused adapters; wrap a non-Chisel IP or core and simulate it in Chipyard. |
| 4a | Same-system multi-version composition prototype | Demonstrate simultaneous elaboration of two compatible aliased generator revisions, distinct instance identities, collision-free RTL and functional simulation; support only after validation. |
| 4b | Generalized composition | Integrate an independent accelerator with a host and memory system. |
| 5 | Additional backends | Use the same system composition across supported simulation, FPGA/FireSim, and synthesis targets, with backend-specific configuration where required. |

The MVP should concentrate on phases 1–3 (including 2b): resolve dependencies, establish entry points, demonstrate separate experiments with aliased revisions, and demonstrate one real internal replacement. Same-system multi-version composition is a follow-on prototype, not a v1 acceptance blocker. A successful build is insufficient on its own; validate the hardware contribution's functional integration and document the exact pinned toolchain.

### 13.1 Dependency and experiment isolation acceptance tests

| Test | Expected result |
|---|---|
| Build two independent workspaces with the same tool release, one for internal replacement and one for a whole generator | Each build starts at its own workspace root and uses its own declarations and lockfile. Switching workspaces requires no edits to the tool or manual registration in Chipyard. |
| Build a project declaring only BOOM with a BlackParrot integration stored locally | BOOM succeeds; BlackParrot sources are excluded and no BlackParrot dependency is fetched. |
| Reference BlackParrot in the selected BOOM system when absent from the resolved graph | Compilation/validation fails with a missing-dependency diagnostic; no automatic fetch. |
| Repeat with BlackParrot already cached | Same failure; cache does not expand the classpath. |
| Use a generator supplied transitively on the selected compile classpath, without a direct declaration | Compilation succeeds; no warning is required for v1. |
| BOOM and BlackParrot declared, BOOM with custom BTB selected | Only the imported BOOM integration unit compiles and only the selected BOOM integration hardware elaborates; both declared external dependencies may be fetched/compiled. |
| Resolve dependencies without an experiment, then switch between BOOM and BlackParrot experiments | Same project-wide dependency graph and lockfile; only selected sources, elaboration and run outputs change. No experiment entries in `build.sbt`. |
| Build BOOM baseline with an intentionally broken but unreferenced BOOM adapter | Baseline succeeds; adapter not passed to compiler. Importing the broken adapter fails. |
| Selected system imports an integration only through an active helper, and that integration imports another optional integration | Both integration units are selected recursively; unrelated units remain excluded. |
| Named import selects an adapter but no factory is invoked | The integration unit compiles but contributes no hardware. |
| An import-like string or comment names an unselected adapter | No selection occurs. |
| Select an integration whose host is not in the dependency graph | Compilation/validation fails; the import cannot trigger a fetch. |
| Switch from custom BTB to baseline with warm caches | Target: prior adapter artifacts cannot leak into the baseline classpath; build plan explains selected/excluded sources. |
| Select baseline, then custom BTB, then a larger BTB | Separate configuration selection and RTL/run outputs; reusable implementation and compatible compilation caches. |
| Unselected experiment references an unavailable host | It does not prevent the selected experiment from compiling. |
| Instantiate a wrapper with no internal replacement capability | Whole-generator build succeeds using default upstream hardware. |
| Request an unsupported replacement | Fail explicitly; do not silently alter the host. |
| Select a supported native replacement in one of two host instances | Only the intended instance is changed; the other uses the default. |
| Build a default system after a custom variant | Default upstream content is unchanged; shared source cache remains immutable; artifacts and manifests are isolated. |
| Declare two BOOM revisions with distinct aliases | Both nodes resolve and are cached; neither is silently evicted; aliases map to distinct compilation identities. |
| Select baseline then experimental alias through imports | Each build uses the intended revision; no `build.sbt` edits, Git switching or lockfile changes are needed. Repeat with warm caches. |
| Declare two different dependency instances with the same alias | Fail with an actionable ambiguity/conflict diagnostic. |
| Import an undeclared alias, including one present in the cache | Compilation/validation fails; no new dependency is fetched. |
| Two aliased revisions require incompatible shared platform dependencies | Resolution fails explicitly rather than silently evicting or duplicating shared infrastructure. |
| Reuse one cached revision under different aliases in independent projects | Immutable source cache can be reused; derived outputs and namespace mappings remain correct and isolated. |

Follow-on prototype checks (not v1 acceptance requirements): instantiate both aliased variants in one SoC, verify each intended revision and hart identity, ensure no Scala/RTL/configuration collisions, and run a functional simulation. An unsupported combination must fail rather than silently bind to the wrong version.

## 14. Decisions and open questions

### 14.1 Established direction

- Hardware research projects should remain independent of a manually maintained Chipyard fork.
- `build.sbt` is authoritative for one project-wide dependency graph, with no configuration-specific dependency declarations or experiment registration. Direct and visible transitive dependencies are usable; external references outside the resolved graph fail regardless of cache contents. Direct-transitive-use warnings are optional post-v1 diagnostics, not a build requirement.
- Explicit dependency aliases allow multiple revisions of a generator within the single project-wide graph. Separate-experiment selection by imports is a v1 target; same-system instantiation requires a successful compatibility prototype. Shared platform infrastructure must remain compatible.
- `Module.scala` should be the canonical top-level hardware entry-point file, analogous to `main.c`, rather than just a naming suggestion.
- A selected `config/<experiment>/System.scala` describes only the intended system; multiple configurations are supported and selected at build time. A system may omit the local module for a baseline.
- Unused adapters/configuration fragments must not elaborate hardware. Named Scala imports are the primary v1 mechanism to select optional integration source units before compilation, independent of host availability. No separate adapter registration or experiment-specific dependency declarations.
- Non-Chisel wrappers need only support whole-generator instantiation. Internal replacement through available extension points is permitted, but wrapper-driven transformations, stubs and post-elaboration replacement orchestration are deferred to future work.

### 14.2 Proposed defaults (not yet confirmed)

| ID | Decision | Proposed default |
|---|---|---|
| D-01 | Entry-point contract | Mandatory `Module.scala` in conforming projects, with a typed construction factory. |
| D-02 | Entry-point symbol | `object Module` with `create(...)`; qualify or alias Chisel's own Module. |
| D-03 | System representation | Standard Chipyard `Config` classes first, higher-level composition API later. |
| D-04 | Replaceability | Explicit typed native or available wrapper-defined extension points; generalized RTL transformations are outside the current scope. |
| D-05 | Legacy generator metadata | Versioned integration recipes with project overrides. |
| D-06 | Conflict handling | Retain explicitly aliased generator revisions; fail on ambiguous aliases or incompatible shared dependencies. No silent dependency or hardware patching. |
| D-07 | Tool and workspace ownership | Established in v0.12. Release the tool independently. Each user workspace declares its hardware and experiments. The tool generates the Chipyard-compatible build workspace. |
| D-08 | Reproducibility | Commit a lockfile and pin sources/toolchain. |
| D-09 | Integration exports | Allow multiple passive host-specific config fragments and adapters; integration source units are selected before compilation, and hardware is selected by the system during elaboration. |
| D-10 | External hardware | Use separately distributed generator wrappers with explicit RTL/build contracts, rather than requiring a Chisel port. |
| D-11 | Experiment layout and selection | `config/<experiment>/System.scala`, selected by the hardware build manager via a build-time configuration name; all experiments share the same project-wide dependency declarations. |
| D-12 | Adapter compilation | **Selected in v0.10:** import-driven selection of isolated integration units before ordinary Scala compilation. Named imports select units recursively; native source-path lookup is an internal alternative only if it preserves this contract. Manual source-unit selection is a fallback/debug option, not the normal workflow. Never expand the external dependency graph. |
| D-13 | External replacement orchestration | Deferred to future work in v0.9; no dedicated transformation or binding stage in the current architecture. |
| D-14 | Multi-version dependencies | **Selected in v0.11:** optional aliases denote distinct dependency instances and import-facing identities. Preserve immutable source caches, one project-wide graph and shared compatible infrastructure. Investigate syntax-aware package relocation for simultaneous use; separate experiments are the v1 target. |

### 14.3 Open questions for the next design iteration

| ID | Question |
|---|---|
| Q-01 | Should the entry point be identified by `object Module`, a common typed trait, a build setting, or some combination? |
| Q-02 | Should `Module.scala` expose a Chisel `RawModule`/`Module` factory only, or also support Diplomacy `LazyModule` and non-Chisel generator exports? |
| Q-03 | How should arbitrary typed parameter sets be supplied to a standalone elaborator without losing type safety? Should the entry point provide a default generator and named presets? |
| Q-04 | Is one canonical export sufficient, or do we need named secondary hardware exports from the start? |
| Q-05 | Should `System.scala` be optional for reusable libraries that are only consumed by other projects? |
| Q-06 | **Resolved in v0.8:** One project-wide dependency graph; no experiment-specific declarations. |
| Q-07 | How should passive integration subprojects declare their prerequisites and export their fragments to downstream consumers? |
| Q-08 | Can native sbt source project references cover the initial build, or is an explicit bootstrap required for Chipyard's legacy project graph and scripts? |
| Q-09 | Which exact Chipyard/BOOM revision and predictor interface should be used for the first BTB integration test? |
| Q-10 | What are the minimal adapter and slot contracts, including timing, speculation, and reset behavior? |
| Q-11 | How should local overrides interact with locked dependencies, submodules, and shared caches? |
| Q-12 | What exact CLI, source layout, and class-entry convention should select `config/<experiment>/System.scala` before source selection and compilation, independently of dependency resolution? |
| Q-13 | What common wrapper contract should cover Chisel, Diplomacy and external RTL generators, including backend capability declarations? |
| Q-14 | Which non-Chisel project should be the first wrapper demonstrator? Reusing CVA6/Ibex glue is a lower-risk starting point than a new BlackParrot tile. |
| Q-15 | Deferred: BlackParrot-specific internal replacement. Not a blocker for whole-core wrapping. |
| Q-16 | Post-v1, should a non-blocking diagnostic recommend explicit declarations for directly used transitive dependencies, and how should it account for exported APIs and platform-provided symbols? (Not a v1 blocker.) |
| Q-17 | How should design-space sweeps pass typed parameter overrides and record effective system parameters without duplicating configurations? |
| Q-18 | Deferred in v0.9: wrapper recipe/binding API and post-elaboration RTL replacement stages. Not an active design question. |
| Q-19 | **Direction resolved in v0.10:** import-driven selection. Prototype a syntax-aware named-import selector, package-to-unit mapping, recursive source closure, and sbt/Zinc source-set integration. Validate supported import forms, downstream source dependencies, cold/warm builds, missing hosts, helper imports, and unused-but-available adapters. The internal mechanism and exact conventions remain open. |
| Q-20 | Validate alias naming, namespace mapping and isolated compilation with sbt/Zinc and the Chisel compiler plugin. Can syntax-aware source relocation preserve BOOM package semantics and integration APIs? Is shading a viable alternative? |
| Q-21 | What alias-aware legacy integration recipes and shared-platform compatibility checks are needed for two revisions? Validate separate-experiment builds first, then simultaneous elaboration, configuration scoping, distinct hart IDs, RTL names/resources and functional simulation. |

Recommended next discussion: resolve Q-01 through Q-03 before designing a generalized `HardwareSystem` DSL. The entry-point contract determines how independent generators can actually be composed and how standalone and host-integrated elaboration relate.

## 15. Reference material

The following public sources ground the existing build/integration context. The new APIs, cache format, and entry-point contract are proposals, not features documented by these sources.

- Chipyard: [Integrating Custom Chisel Projects into the Generator Build System](https://chipyard.readthedocs.io/en/stable/customization/custom-chisel/).
- Chipyard: [Top-level build.sbt](https://github.com/ucb-bar/chipyard/blob/main/build.sbt) (moving branch; pin a commit before implementation).
- Chipyard: [common.mk](https://github.com/ucb-bar/chipyard/blob/main/common.mk) (existing backend and source-layout assumptions).
- BOOM: [Development Ecosystem](https://docs.boom-core.org/en/latest/sections/boom-ecosystem.html).
- sbt: [Multi-project builds](https://www.scala-sbt.org/1.x/docs/Multi-Project.html), [Organizing the build](https://www.scala-sbt.org/1.x/docs/Organizing-Build.html), and [Plugins](https://www.scala-sbt.org/1.x/docs/Plugins.html).

- Chipyard: [Configuration fragments](https://chipyard.readthedocs.io/en/stable/chipyard-basics/configs-parameters-mixins/), [Incorporating Verilog blocks](https://chipyard.readthedocs.io/en/latest/customization/incorporating-verilog-blocks/), and [Adding a custom core](https://chipyard.readthedocs.io/en/stable/customization/custom-core/).
- Chipyard: [CVA6 integration](https://chipyard.readthedocs.io/en/stable/generators/cva6/) and [Ibex integration](https://chipyard.readthedocs.io/en/stable/Generators/Ibex.html).
- BlackParrot: [upstream repository](https://github.com/black-parrot/black-parrot) and [microarchitecture guide](https://github.com/black-parrot/black-parrot/blob/master/docs/microarchitecture_guide.md).

- Scala: [Scala 2 compiler options (-sourcepath)](https://docs.scala-lang.org/overviews/compiler-options/), [Scala 2.13.18 SymbolLoaders](https://www.scala-lang.org/api/2.13.18/scala-compiler/scala/tools/nsc/symtab/SymbolLoaders.html), and [Optimizer](https://docs.scala-lang.org/overviews/compiler-options/optimizer.html). Source-path integration with sbt/Chisel has not been tested.

## 16. Revision history

| Version | Change |
|---|---|
| 0.1 | Initial architecture discussion: dependency resolution, system composition, adapter architecture, caching, and implementation roadmap. |
| 0.2 | Incorporated the clarification that `Module.scala` is the canonical hardware entry point analogous to `main.c`; separated the entry-point file, exported Scala symbol, and elaboration-time factory; added requirements, explicit decisions, open questions, and acceptance criteria. |
| 0.3 | Added multiple host-specific configuration fragments, selectable integration dependency/source scopes, independently distributed generator wrappers, external RTL build contracts, and the distinction between wrapping a whole external core and replacing an internal component. Added BlackParrot as a candidate case study, not a verified integration. |
| 0.4 | Made `build.sbt` authoritative with strict missing-dependency failures, separated dependency availability and adapter source compilation from passive elaboration, moved system entry points into selectable experiment configurations, and added scoped builds, reproducibility metadata and isolation acceptance tests. |
| 0.5 | Established optional wrapper-owned internal replacement for non-Chisel generators, including explicit upstream source patches and RTL stub/substitution recipes; added staged binding, instance isolation, validation and reproducibility requirements; removed cross-language internal replacement from mandatory milestones. |
| 0.6 | Allowed direct use of resolved transitive dependencies; retained `build.sbt` as the authoritative dependency-root declaration, missing-dependency failures outside the graph, and cache isolation. Deferred optional direct-transitive-use warnings beyond v1. Updated requirements, build flow, and acceptance tests. |
| 0.7 | Distinguished host availability from integration source demand; documented desired implicit adapter selection, Scala 2 source-path investigation, named-import selector and explicit subproject fallback, with additional acceptance tests and an open implementation decision. |
| 0.8 | Removed experiment-specific dependency declarations and scopes; all configurations and standalone builds share one project-wide dependency graph and lockfile. Moved experiment selection to the hardware build manager, kept demand-driven integration source selection separate, and revised CLI sketches, build flow, requirements and acceptance tests. Resolved Q-06. |
| 0.9 | Deferred wrapper-driven source/RTL replacement transformations, intermediate stubs and post-elaboration binding to future work. Removed the dedicated replacement pipeline, roadmap milestone and specialized acceptance tests from current scope. Retained ordinary external RTL wrapping and replacement through available extension points. |
| 0.10 | Selected import-driven adapter source selection as the primary v1 design. Specified a named-import contract, recursive selection of active integration units, package mapping, isolation and acceptance tests. Native source-path lookup is an internal alternative only; explicit selection is a fallback/debug option. Dependency policy and other architecture decisions unchanged. |
| 0.11 | Incorporated optional dependency aliases as distinct project-wide dependency instances, multi-revision caching, import-facing namespaces, separate-experiment selection, shared-platform compatibility constraints, and a same-system composition prototype. Updated requirements, resolution, source selection, system examples, cache/lockfile, roadmap and acceptance tests. Preserved manually revised project layout from v0.10. |
| 0.12 | Established the tool and user workspace as independently owned projects. Distinguished generated build files from workspace inputs and added a cross-workspace acceptance test. |
