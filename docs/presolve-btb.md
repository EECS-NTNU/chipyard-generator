# Replace the BOOM BTB with Presolve

Use `presolve create-project` to start a hardware workspace and configure it to build two BOOM v3 systems. The `baseline` experiment uses stock `SmallBoomV3Config`. The `custom` experiment swaps the main branch target buffer (BTB) through BOOM’s `branchPredictor` hook. Both experiments use the same pinned Chipyard platform and run the same RISC-V program. [BTB fixture](../fixtures/boom-btb)

## Install Presolve and configure the Chipyard toolchain

Use Bash on Linux x86-64 with Git, sbt, and a JDK version 11 or newer on `PATH`. Use paths without whitespace or shell metacharacters for the toolchain, workspace, and cache. From the Chipyard Generator checkout, start the guided setup.

```bash
generator_repo="$PWD"
bash cli/setup.sh
```

Setup publishes the sbt plugin locally and stages the `presolve` launcher. At the toolchain prompt, choose the path that matches your machine.

### Install Chipyard with Conda

Have a working `conda` command on `PATH` before starting setup. If `CHIPYARD_TOOLCHAIN_PREFIX` is already set and you want a fresh installation, unset it in the setup shell first so the script presents the choice. Choose `i` at the toolchain prompt, accept the suggested checkout directory or enter a new one, and answer `y` to start the installation. The script checks out the pinned Chipyard revision and runs its `build-setup.sh` to install the toolchain, including the RISC-V tools. The toolchain prefix is the `.conda-env` directory inside that checkout. [Pinned toolchain requirements](../reference/chipyard-rocket/README.md#toolchain)

### Use your existing Chipyard installation

Choose `e` at the toolchain prompt and enter the absolute path to your toolchain prefix. For a Chipyard checkout set up with Conda, enter `/path/to/chipyard/.conda-env`. Use the directory that contains `bin/`, `lib/jvm/`, and `riscv-tools/`. Setup checks for the required tools, including JDK 20, Verilator 5.022, and `firtool-1.75.0`. If you already export a compatible `CHIPYARD_TOOLCHAIN_PREFIX`, setup detects it and skips the prompt. [Reference toolchain versions](../reference/chipyard-rocket/README.md#toolchain)

### Activate Presolve and check setup

For either path, look for `Using Chipyard toolchain at ...` in the setup output. Answer `n` when setup asks to create a workspace so you can create it explicitly in the next step. Then activate Presolve in the same shell.

```bash
source cli/target/presolve-env.sh
presolve --help
printf '%s\n' "$CHIPYARD_TOOLCHAIN_PREFIX"
```

The help output should list `create-project`, `resolve`, `verify`, `compile`, and `run`. The printed prefix should point to the toolchain you selected. These steps configure the tools that build and run the simulator. Later, `presolve resolve` fetches the pinned Chipyard and BOOM sources for this workspace.

## Create a workspace

```bash
workspaces=$(mktemp -d)
presolve create-project "$workspaces/boom-btb"
cd "$workspaces/boom-btb"
```

`create-project` should print `Created workspace at ...`. The new directory should contain `build.sbt`, `project/plugins.sbt`, and `config/baseline/System.scala`.

## Configure the BOOM experiments

The scaffold includes a pinned Chipyard version, a stock Rocket baseline, and a starter Chisel module. In the generated `build.sbt`, replace `hardwareDependencies := Seq.empty` with the setting below to select the pinned platform’s BOOM source and its dependencies. [Fixture build](../fixtures/boom-btb/build.sbt)

```scala
hardwareDependencies := Seq(Generator.chipyard("boom"))
```

From the new workspace, replace the starter module and Rocket configuration with the fixture’s BTB parameters and BOOM baseline. Add the custom experiment and its adapter.

```bash
btb_fixture="$generator_repo/fixtures/boom-btb"
mkdir -p config/custom src/main/scala/btbfixture
cp "$btb_fixture/config/baseline/System.scala" config/baseline/System.scala
cp "$btb_fixture/config/custom/System.scala" config/custom/System.scala
cp "$btb_fixture/src/main/scala/btbfixture/Module.scala" src/main/scala/btbfixture/Module.scala
cp -R "$btb_fixture/integrations/boom" integrations/
rm src/main/scala/workspace_boom_btb/Module.scala
```

The baseline extends `chipyard.SmallBoomV3Config` directly. The custom experiment uses the same base configuration with a named import and a config fragment that overrides predictor construction. That import tells Presolve to compile `integrations/boom` for the custom build. [Baseline configuration](../fixtures/boom-btb/config/baseline/System.scala) · [Custom configuration](../fixtures/boom-btb/config/custom/System.scala)

## Resolve dependencies and select an experiment

```bash
presolve experiments
presolve resolve
presolve verify
```

`experiments` should list `baseline` and `custom` from their `config/<experiment>/System.scala` entry points. After `resolve`, look for `hardware.lock` in the workspace root. `verify` should succeed and checks the resolved graph against the lockfile without rewriting it. Commit `hardware.lock` in your own repository and rerun `resolve` when you intentionally change a dependency declaration or a declared local dependency’s contents.

Use `presolve compile baseline` or `presolve compile custom` to generate RTL only. Use `presolve build` to elaborate a system and build its simulator before running an ELF.

## Run both systems with the same program

Build the branch smoke-test ELF from the bundled assembly and linker script. The program runs a loop with a taken branch and reports whether the loop computed the expected result. [Branch workload](../tests/acceptance/payloads/branches.S)

```bash
payload_source="$generator_repo/tests/acceptance/payloads"
"${CHIPYARD_TOOLCHAIN_PREFIX:?Set the Chipyard toolchain prefix}/riscv-tools/bin/riscv64-unknown-elf-gcc" \
  -march=rv64imac_zicsr -mabi=lp64 -nostdlib -static \
  -Wl,--build-id=none,-T,"$payload_source/htif.ld" \
  "$payload_source/branches.S" -o branches.elf

set -o pipefail
presolve build baseline
presolve build custom
presolve run baseline ./branches.elf 2>&1 | tee baseline.log
EXTRA_SIM_FLAGS='+verbose' presolve run custom ./branches.elf 2>&1 | tee custom.log
```

Both runs should report `*** PASSED ***`. With `+verbose`, the custom run should also print `Custom BTB trained a taken branch`, which shows the custom bank received a committed taken-branch update.

## Verify which BTB was generated

Presolve prints the selected simulator directory after `Using Chipyard build:`. The generated Verilog and SystemVerilog live under `chipyard/sims/verilator/generated-src` within that directory.

```bash
baseline_run=$(sed -n 's/^\[info\] Using Chipyard build: //p' baseline.log)
custom_run=$(sed -n 's/^\[info\] Using Chipyard build: //p' custom.log)

for run_dir in "$baseline_run" "$custom_run"; do
  grep -r -E --include='*.sv' --include='*.v' \
    '^[[:space:]]*module[[:space:]]+(BTBBranchPredictorBank|FixtureBTB)([[:space:](#;]|$)' \
    "$run_dir/chipyard/sims/verilator/generated-src"
done
```

Expect only `BTBBranchPredictorBank` in the baseline results and only `FixtureBTB` in the custom results. The build's `run.json` records the experiment, lock fingerprint, and toolchain. Under `sourceSelection.activeScalaSources`, the custom build manifest includes `workspace:integrations/boom/src/main/scala/WithFixtureBTB.scala`. The baseline manifest omits that adapter. The simulation directory printed after `Generated Chipyard run:` contains an ELF checksum and build reference in its own `run.json`. [Acceptance checks](../tests/acceptance/generator-simulation.sh)

## Integrate your own BTB

Use the generated workspace as your BTB repository. Put your reusable Chisel implementation under `src/main/scala` and keep the BOOM-specific wiring in `integrations/boom/src/main/scala`. The fixture uses `btbfixture.Module` to supply dimensions and subclasses BOOM’s stock `BTBBranchPredictorBank`. For your own implementation, adapt it to BOOM v3’s `BranchPredictorBank` interface, including the staged prediction responses and update path. Instantiate that bank in the `branchPredictor` constructor supplied by `WithFixtureBTB`. Preserve the connections to the other predictor banks, including the micro-BTB. [Fixture parameters](../fixtures/boom-btb/src/main/scala/btbfixture/Module.scala) · [BOOM adapter](../fixtures/boom-btb/integrations/boom/src/main/scala/WithFixtureBTB.scala)

Keep the `baseline` experiment for comparison. After changing your implementation, rerun `presolve build custom` followed by `presolve run custom ./branches.elf`. Update the RTL check to use your bank’s module name. If your BTB exposes a `Module.create()` factory that builds it on its own, `presolve standalone` can also generate its RTL outside the BOOM system. [Standalone modules and integration layout](../README.md#standalone-modules-and-optional-integrations)

### Add a LiteBTB experiment

LiteBTB stores each distinct BTB tag in a set-associative **Unique Tag Buffer** (UTB). A BTB entry holds a compact pointer to the UTB entry alongside its target and branch metadata. The fetch PC indexes the BTB and UTB in parallel. The UTB uses the lower tag bits as its set index and compares the upper bits within that set. A UTB hit yields a set-and-way pointer. A valid BTB entry for the requested instruction slot hits when its stored pointer matches. A UTB miss is a BTB miss. [LiteBTB paper, Section IV and Figure 4](https://roman.brunner.best/assets/pdf/LiteBTB_MICRO26.pdf)

#### Implement the lookup and update logic

1. Add `LiteBTB.scala` under `src/main/scala/btbfixture` for the UTB and pointer-based BTB arrays. Keep the fixture’s existing `btbfixture.Module` as the workspace’s single `Module.scala` entry point. Give the BTB and UTB independent set and way parameters. Use BOOM’s fetch-block index and tag split at the adapter boundary and retain separate branch slots within a bank. Start with the same BTB geometry and target representation as your comparison bank so the experiment isolates the change in tag storage.
2. Add `LiteBTBBranchPredictorBank` under `integrations/boom/src/main/scala` in package `btbfixture.integrations.boom`. Extend BOOM v3’s `BranchPredictorBank` and instantiate your `LiteBTB` there. Launch both reads from `f0_pc` and align the results with BOOM’s F1 lookup and F2/F3 responses. Pass the incoming prediction through on misses and for masked slots. On hits, supply the target and branch classification, preserving conditional direction predictions and BOOM’s taken behavior for JAL. Declare the bank’s `mems` and carry any hit or replacement metadata through `f3_meta` and the matching update metadata.
3. Use BOOM’s valid commit updates for training. Use `cfi_idx` to locate the target slot, `cfi_taken` and `target` for taken-target writes, and `br_mask` for conditional branch metadata. Probe the UTB with the update’s tag and reuse its pointer on a hit. On a miss, allocate a UTB entry and write the new pointer with the BTB entry. Define how a lookup sees simultaneous writes and how you handle an update whose prediction metadata refers to an entry that has since been replaced. [Pinned BOOM v3 BTB](https://github.com/riscv-boom/riscv-boom/blob/ba03175e0a3cf5c0d5a7f14d385ac1c8998c4428/src/main/scala/v3/ifu/bpd/btb.scala) · [Predictor bank interface](https://github.com/riscv-boom/riscv-boom/blob/ba03175e0a3cf5c0d5a7f14d385ac1c8998c4428/src/main/scala/v3/ifu/bpd/predictor.scala)

A UTB eviction can leave several BTB entries pointing at the old slot. If that slot is reused, a stale pointer can produce a false hit. For a first prototype, invalidate entries that reference a victim before reusing its pointer. If invalidation takes multiple cycles, suppress hits using that pointer until invalidation completes and account for in-flight lookups. The paper uses reactive scrubbing when decode detects an alias. BOOM v3’s existing `btb_mispredicts` feedback covers incorrect predicted JAL targets when fast repair is enabled. Reproducing the paper’s scrubbing also requires feedback for predicted branch types that disagree with decode and predictions on non-branches. [LiteBTB paper, Section IV-C](https://roman.brunner.best/assets/pdf/LiteBTB_MICRO26.pdf) · [Pinned BOOM frontend](https://github.com/riscv-boom/riscv-boom/blob/ba03175e0a3cf5c0d5a7f14d385ac1c8998c4428/src/main/scala/v3/ifu/frontend.scala#L703)

#### Wire the bank into BOOM and run it

Use `WithFixtureBTB` as the template for a `WithLiteBTB` config fragment in the same integration package. In the `branchPredictor` constructor, instantiate `LiteBTBBranchPredictorBank` in the main BTB position. Keep the micro-BTB, BIM, TAGE, and loop banks and their wiring. Add `config/litebtb/System.scala` with a named import so Presolve selects the adapter when building `litebtb`.

```scala
package btbfixture

import btbfixture.integrations.boom.WithLiteBTB
import org.chipsalliance.cde.config.Config

class System extends Config(new WithLiteBTB ++ new chipyard.SmallBoomV3Config)
```

Use the lockfile from the earlier `resolve` step and the same `branches.elf` as the stock baseline.

```bash
presolve experiments
presolve verify
presolve build litebtb
presolve run litebtb ./branches.elf
```

Check for `*** PASSED ***`, then use the [RTL check above](#verify-which-btb-was-generated) with the `litebtb` run directory. Look for `LiteBTBBranchPredictorBank` in place of the stock main BTB and check `run.json` for `experiment: litebtb` and the selected adapter. Add directed tests that train branches in different BTB sets with the same tag, force a UTB eviction and pointer reuse, and check that the old target never hits in the prototype. Also test multiple instruction slots and simultaneous lookup and update. The bundled ELF checks basic execution. Use larger branch footprints to measure the effect on BTB misses and account for UTB storage, pointers, and eviction metadata when comparing storage.
