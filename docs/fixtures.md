# Writing a fixture for a custom hardware structure

A fixture is a small, reproducible example used to exercise a specific behavior. In this repository, `fixtures/` contains Presolve workspaces and supporting source projects used by the acceptance tests. A fixture workspace uses the same `build.sbt`, dependency lock, and experiment layout as any Presolve workspace. An experiment is one system configuration within that workspace. A replacement fixture can put the stock system and the replacement in two experiments so both can run the same workload against the same dependency lock. [Chipyard acceptance tests](../tests/acceptance/README.md)

The BOOM branch target buffer (BTB) fixture tests whether an experiment can install a different predictor bank through BOOM v3’s configuration hook. Its replacement subclasses the stock bank, sets its dimensions to 64 sets and four ways, and prints a message on the first committed taken-branch update. This gives the test observable evidence that BOOM instantiated the replacement and sent it an update. [BTB fixture](../fixtures/boom-btb)

## Create the workspace and pick the host hook

With Presolve and the Chipyard toolchain set up, run `presolve create-project my-structure` for a fresh workspace. If you are replacing a BOOM v3 BTB, you can start with a copy of the working fixture. Run these commands from the Chipyard Generator checkout.

```bash
workspaces=$(mktemp -d)
cp -R fixtures/boom-btb "$workspaces/my-btb"
cd "$workspaces/my-btb"
```

Choose the host configuration and identify the hook that constructs or attaches your structure. Put the pinned platform and the generator dependencies in `build.sbt`. For BOOM, the fixture uses `hardwareDependencies := Seq(Generator.chipyard("boom"))`. For a structure outside the core, choose the attachment or binder provided by its host. Check the hook against the pinned source revision before writing the adapter. [Workspace declarations](../README.md#workspace-declarations) · [BTB build declaration](../fixtures/boom-btb/build.sbt) · [Harness binder example](../fixtures/wrapper-consumer/config/default/System.scala)

## Write the structure and its adapter

1. Put reusable hardware and its parameters under `src/main/scala`. Use a named Scala package and keep exactly one `Module.scala` with a top-level `object Module` if the workspace has optional integrations. Replace the generated starter file if you add your own `Module.scala`. The package declaration in this file determines the integration namespace. If the structure can elaborate independently, expose a `Module.create()` factory callable with no arguments and use `presolve standalone` to generate its RTL. The BTB fixture’s `Module` object supplies dimensions. Its host-dependent predictor bank is defined in the adapter. [BTB parameters](../fixtures/boom-btb/src/main/scala/btbfixture/Module.scala) · [Standalone factory example](../fixtures/select/src/main/scala/selectfixture/Module.scala)
2. Put host-specific wiring under `integrations/<unit>/src/main/scala`, use a Scala identifier for `<unit>`, and declare the adapter’s package as `<module-package>.integrations.<unit>`. For the BTB, `Module.scala` declares `package btbfixture`, the unit directory is `integrations/boom`, and the adapter declares `package btbfixture.integrations.boom`. If you rename the module package, update both the adapter package and the experiment’s import. [Integration layout](../README.md#standalone-modules-and-optional-integrations)
3. Make the adapter install the hardware through the host hook. In the BTB fixture, `WithFixtureBTB` updates the BOOM v3 tiles’ `branchPredictor` factory. It instantiates `FixtureBTB` and preserves the other banks and their response chain, including the micro-BTB. When adapting your own BTB, satisfy BOOM’s `BranchPredictorBank` interface, including its staged responses and update path. The fixture reports a diagnostic for hosts without a BOOM v3 tile. [BOOM adapter](../fixtures/boom-btb/integrations/boom/src/main/scala/WithFixtureBTB.scala)

## Define the baseline and custom experiments

Give each experiment a `config/<experiment>/System.scala` file with a top-level `System` class in a named package. Use the stock host configuration in `baseline`. In `custom`, import the adapter by name and compose its fragment to the left of the same base configuration so the fragment can override the host parameters. Presolve uses the named import to select the optional integration’s sources. A wildcard import alone cannot select the unit. [Stock BOOM baseline](../fixtures/boom-btb/config/baseline/System.scala) · [Custom experiment](../fixtures/boom-btb/config/custom/System.scala)

```scala
package btbfixture

import btbfixture.integrations.boom.WithFixtureBTB
import org.chipsalliance.cde.config.Config

class System extends Config(new WithFixtureBTB ++ new chipyard.SmallBoomV3Config)
```

## Verify the fixture

From the workspace root, resolve and verify the dependencies, then elaborate each experiment to check that both configurations build. The directory under `config/` supplies the experiment name. For example, use `presolve compile litebtb` for `config/litebtb/System.scala`, or `presolve build litebtb` to produce a simulator for `presolve run`. Keep the resulting `hardware.lock` with the workspace for repeatable builds.

```bash
presolve experiments
presolve resolve
presolve verify
presolve compile baseline
presolve compile custom
```

Run both experiments with the same compatible RISC-V ELF. Choose a workload that exercises the structure and check a result specific to that behavior, such as an assertion or a simulation marker. The BTB fixture uses a branch loop and expects `*** PASSED ***` in both runs. With verbose simulation enabled, the custom run also reports `Custom BTB trained a taken branch`. [Build and run the branch workload](presolve-btb.md#run-both-systems-with-the-same-program)

Use the generated RTL to verify that the baseline instantiates the stock structure and the custom system instantiates the replacement. Check `run.json` under `sourceSelection.activeScalaSources` to confirm the adapter was compiled for `custom` and omitted from `baseline`. If you add the fixture to this repository’s acceptance suite, keep the workload and checks alongside the acceptance scripts. The BTB acceptance check also builds an `unsupported` experiment against Rocket and checks for the missing-hook diagnostic. [RTL and manifest checks](presolve-btb.md#verify-which-btb-was-generated) · [BTB acceptance script](../tests/acceptance/generator-simulation.sh)

To record effective settings, call `presolve.ElaborationParameters.report` in the workspace's system or integration code when it instantiates the hardware. Pass a component name and named JSON values, such as `"sets" -> JInt(params.nSets)` with `org.json4s.JInt`. Use the same parameters that the constructor receives. Include a tile or instance identifier when components can have different settings. Successful system builds list the reports under `parameters` in the build's `run.json`. An experiment with no reports has an empty `parameters` object. The simulation manifest links to the build it used. [BTB reporting example](../fixtures/boom-btb/integrations/boom/src/main/scala/WithFixtureBTB.scala)
