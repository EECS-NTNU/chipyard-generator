# Chipyard Rocket reference simulation

The reference uses the stock `chipyard.RocketConfig` and Chipyard's upstream Verilator Makefiles at commit `a7946ede9bd625204435715dc40c750e2f8a251f`. [Upstream simulation guide at the pinned commit](https://github.com/ucb-bar/chipyard/blob/a7946ede9bd625204435715dc40c750e2f8a251f/docs/Simulation/Software-RTL-Simulation.rst)

## Toolchain

| Tool | Version |
| --- | --- |
| OpenJDK | 20.0.2-internal-adhoc..src (Conda build `haa376d0_2`) |
| sbt | 1.8.2 |
| Scala | 2.13.16 |
| Chisel | 6.7.0 (`org.chipsalliance`) |
| CIRCT | `firtool-1.75.0` |
| Verilator | 5.022 (Conda build `h7cd9344_1`) |
| RISC-V tool bundle | `riscv-tools-1.0.6-0_h1234567_g56c29e0` |
| RISC-V GCC | 13.2.0 (`gc891d8dc23e`) |
| RISC-V objcopy | GNU Binutils 2.42 |
| Host C++ compiler | GCC 13.2.0 (Conda build `hc7bed06_13`) |
| GNU Make | 4.3 |

The run used a preinstalled Linux x86-64 Chipyard Conda environment with these versions. The toolchain prefix contains `bin/`, `lib/jvm/` and `riscv-tools/`. The runner uses Maven Central and the sbt plugin repository for the dependencies of the pinned upstream build.

The [Presolve setup script](../../cli/setup.sh) can install a compatible toolchain using this revision's upstream installer. Conda must be available on `PATH` for that option. [Upstream Conda setup instructions](https://github.com/ucb-bar/chipyard/blob/a7946ede9bd625204435715dc40c750e2f8a251f/docs/Chipyard-Basics/Initial-Repo-Setup.rst#default-requirements-installation)

## Reproduce

From this tool repository, initialize a fresh upstream checkout. Set `CHIPYARD_TOOLCHAIN_PREFIX` to the preinstalled environment described above.

```sh
revision=a7946ede9bd625204435715dc40c750e2f8a251f
work=$(mktemp -d)
git init "$work/chipyard"
git -C "$work/chipyard" remote add origin https://github.com/ucb-bar/chipyard.git
git -C "$work/chipyard" fetch --depth=1 origin "$revision"
git -C "$work/chipyard" checkout --detach "$revision"
(cd "$work/chipyard" && ./scripts/init-submodules-no-riscv-tools.sh)
git -C "$work/chipyard" submodule update --init --recursive --depth=1 -- toolchains/riscv-tools/riscv-tests

reference/chipyard-rocket/run.sh "$work/chipyard" "$CHIPYARD_TOOLCHAIN_PREFIX" "$work/output"
```

Use a directory on shared storage if running under a scheduler. The runner builds the payload with the pinned `riscv-tests` Makefile, builds the simulator with `make CONFIG=RocketConfig` in `sims/verilator`, and runs `make CONFIG=RocketConfig RANDOM_SEED=1 run-binary-fast BINARY=<payload>` in the same directory. [Recorded submodule commits](submodules.tsv)

## Payload and pass criterion

- Payload source is `isa/rv64ui/simple.S` in `riscv-tests` at `0494f954a3d8d2ca9e4972da7a01e94b6a909bce`, with `riscv-test-env` at `4fabfb4e0d3eacc1dc791da70e342e4b68ea7e46`.
- The `rv64ui-p-simple` ELF has SHA-256 `e3b197523cb9335246d472affe658d92e5b6240a2faec3699c454cbae0e9bf54` after `objcopy --strip-debug`. The digest omits build-path-dependent debug data. The simulator runs the original ELF.
- The test signals success over HTIF. Expect the simulator and `make run-binary-fast` to exit with status 0 and the runner to print `REFERENCE PASS`.

## Verified result

On 25 November 2026, Slurm job `26344831` on the Idun CPU partition built the simulator, ran the payload with seed `1`, and exited with status `0`. The simulator ended at Verilog `$finish`, and the runner printed `REFERENCE PASS`. Building the payload in two different directories produced the same checksum after stripping debug data.
