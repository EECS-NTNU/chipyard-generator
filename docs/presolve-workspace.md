# The Presolve Workspace

`presolve create-project <directory>` scaffolds a workspace with a starter Chisel module and a stock Rocket experiment named `baseline`. A workspace can hold several experiments, such as a stock BOOM system and a variant with a custom branch target buffer. The experiments share the workspace's dependency declarations and lockfile. [Presolve BTB tutorial](presolve-btb.md)

## Directory layout

| Path | Purpose |
| --- | --- |
| `build.sbt` | Enables `ChipyardPlugin` and declares workspace-wide settings, including `chipyardVersion` and `hardwareDependencies`. |
| `project/build.properties` | Pins the sbt version. |
| `project/plugins.sbt` | Declares the Chipyard Generator sbt plugin and its version. |
| `src/main/scala/` | Holds shared Chisel and Scala sources, including the starter `Module.scala`. |
| `src/test/scala/` | Directory scaffolded for Scala tests. |
| `config/<experiment>/System.scala` | Defines the experiment's top-level `System` class in a named Scala package. For Chipyard experiments, `System` extends Chipyard's `Config` directly or through another config. Put experiment-specific helpers in the same experiment directory. |
| `integrations/<unit>/src/main/` | Holds optional integration code under `scala/` and resources under `resources/`. |
| `hardware.lock` | Records the resolved hardware dependency graph. Create or update it with `presolve resolve` and commit it. |
| `target/hardware/` | Holds generated build plans, RTL and simulator builds under `builds/`, and simulation results under `runs/`. The scaffold ignores `target/` in Git. |

Presolve finds the workspace by looking for the nearest `build.sbt` in the current directory or its parents, then runs sbt in that directory. sbt reads the files in `project/` to load the build. Add hardware source dependencies in the root `build.sbt`, where the plugin reads `hardwareDependencies` to resolve the shared graph. [Workspace declarations](../README.md#workspace-declarations)

Put reusable module code in `src/main/scala/` and use each experiment's `System.scala` to select and configure the system for that experiment. An optional integration is selected by a named import, such as `import myworkspace.integrations.boom.WithCustomBTB`, from the experiment or another source included in its build. The package prefix before `.integrations` matches the owning `Module.scala` package. [Standalone modules and optional integrations](../README.md#standalone-modules-and-optional-integrations)
