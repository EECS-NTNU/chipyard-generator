# Rocket with BISMO

The `acceleratorEvaluation` sbt project depends on Chipyard and on the
catalogued `bismo` project. A normal Chipyard build does not need to fetch
BISMO. Both evaluation configs share the same compiled accelerator.

| Config | Rocket cores | Attachment | BISMO array |
| --- | --- | --- | --- |
| `BismoRocketConfig` | 1 | RoCC custom-0 | 2 × 64 × 2 |
| `WideBismoRocketConfig` | 1 | RoCC custom-0 | 4 × 64 × 2 |

The configurations in `generators/accelerator-evaluation/src/main/scala/System.scala`
select the adapter, opcode, and generator parameters. The RoCC adapter exposes
the register interface from the Vetleh reference. Function 0 writes the value
from the low 32 bits of `rs1` to the register indexed by `rs2`. Function 1 returns the register
indexed by `rs2`. Register 0 requests a soft reset and should only be used
while the accelerator is idle. Register 27 starts instruction generation and
register 26 reports instruction generator readiness. Software controls the
three execution stages with registers 2 through 4. DMA uses aligned 64-bit
beats and 32-bit physical addresses through Rocket's TileLink master port.
BISMO runs on the Rocket tile clock and shares the tile reset.

## Build and smoke test

Use the fork's normal Chipyard setup, including the RISC-V toolchain,
CIRCT, and Verilator. The examples use Chipyard's default stock Chisel 6.
Use JDK 17 for the build, including Chipyard's legacy tapeout utilities.

```sh
sbt 'bismo/fetchPinnedSource' 'acceleratorEvaluation/compile'

make -C sims/verilator -j8 SUB_PROJECT=acceleratorEvaluation

riscv64-unknown-elf-gcc -O2 -march=rv64imafd -mabi=lp64d -mcmodel=medany \
  -specs=htif.specs -static -T "$PWD/tests/htif.ld" \
  tests/bismo-rocc.c -o /tmp/bismo-rocc.riscv
make -C sims/verilator SUB_PROJECT=acceleratorEvaluation \
  BINARY=/tmp/bismo-rocc.riscv LOADMEM=1 run-binary
```

Add `CONFIG=WideBismoRocketConfig` to both make invocations to select the
second experiment. The smoke test reads the geometry, checks register
address decoding, and verifies that the cycle counter starts and stops when
software writes its enable register. It then runs one binary matrix
multiplication tile and checks every result written back through DMA.

## Source catalog

The entry in `build.sbt` pins [Vetleh's separate BISMO repository](
https://github.com/Vetleh/BISMO_Chisel_3/tree/8cf4329c7cd77adf9500ca06eafe6d4f4b41095a)
to the gitlink used by the reference Chipyard branch. BISMO's own gitlink
pins fpga-tidbits to `268de135d0031beddea94e35cf58f7b571753939`.

The fetch task verifies the Git revision and checkout state before exposing the
source directories. The compatibility patch applies to copies under sbt's
managed source directory. It removes legacy FPGA and tester APIs and corrects
instruction generator completion under queue backpressure and the wiring for
rectangular arrays. Chipyard supplies
the compiler and Chisel dependencies.

For another accelerator, add a catalog entry and an sbt project using
`PinnedAccelerator.settings`, then depend on that project from the system's
sbt project. A new experiment using BISMO only needs a configuration in
`System.scala` or another experiment's system definition.

For each run, record the selected configuration, platform Git commit, and
resolved upstream revisions. Commit platform changes before recording an
experiment.

```sh
git rev-parse HEAD
sbt 'bismo/verifyPinnedSource'
git -C .accelerator-sources/bismo/8cf4329c7cd77adf9500ca06eafe6d4f4b41095a \
  rev-parse HEAD
git -C .accelerator-sources/bismo/8cf4329c7cd77adf9500ca06eafe6d4f4b41095a \
  submodule status --recursive -- fpga-tidbits
```
