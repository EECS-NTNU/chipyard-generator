# Build and run target software with Presolve

Build target software with the standard Chipyard tools. Use the Chipyard revision in your workspace's `chipyardVersion` setting and its pinned toolchain. Presolve builds the Verilator simulator for the experiment you select and runs a RISC-V ELF on it.

## Build a Linux boot image

1. Use a writable Chipyard checkout at the workspace's pinned revision. Follow Chipyard's setup instructions with FireMarshal enabled, then source the checkout's `env.sh`. The Presolve installer and runtime image skip FireMarshal setup. If you already have a full toolchain checkout from the Presolve installer, initialize FireMarshal by running `git submodule update --init software/firemarshal` from the Chipyard root and `./init-submodules.sh` from `software/firemarshal`. See FireMarshal's setup notes for host dependencies such as `guestmount` on machines without sudo. [Chipyard setup at the Presolve pin](https://github.com/ucb-bar/chipyard/blob/a7946ede9bd625204435715dc40c750e2f8a251f/docs/Chipyard-Basics/Initial-Repo-Setup.rst#setting-up-the-chipyard-repo) · [FireMarshal setup](https://github.com/firesim/FireMarshal/blob/61fae80f49b97366254720f3b07ee8f8da762799/README.md#setup)
2. Follow the FireMarshal quick start to build and boot the stock Buildroot workload in QEMU. For a custom kernel, create a workload that inherits from `br-base.json` and set `linux.config` for kernel configuration fragments or `linux.source` for a different kernel tree. [Chipyard FireMarshal guide](https://chipyard.readthedocs.io/en/1.14.0/Software/FireMarshal.html) · [FireMarshal quick start](https://firemarshal.readthedocs.io/en/stable/Tutorials/quickstart.html) · [Kernel settings](https://firemarshal.readthedocs.io/en/stable/workloadConfig.html#linux)
3. For Presolve's single-ELF run command, build with `--no-disk` to bundle OpenSBI, Linux, and the root filesystem in one boot ELF. From `software/firemarshal`, select the pinned FireMarshal revision's Chipyard board and build the workload. The stock build writes `images/chipyard/br-base/br-base-bin-nodisk`. [FireMarshal command reference](https://firemarshal.readthedocs.io/en/stable/commands.html)

   ```bash
   export MARSHAL_BOARD_DIR="$PWD/boards/chipyard"
   ./marshal --no-disk build br-base.json
   ```

## Boot Linux on a generated system

From the resolved Presolve workspace, build the baseline simulator and pass it the boot ELF. For a container run, put the ELF in the mounted workspace so the simulator can access it.

```bash
boot_elf=/absolute/path/to/br-base-bin-nodisk
presolve build baseline
LOADMEM=1 timeout_cycles=1000000000 presolve run baseline "$boot_elf"
```

`LOADMEM=1` preloads the ELF into simulated memory, and `timeout_cycles` raises Chipyard's default cycle limit for the Linux boot. Presolve uses Chipyard's `run-binary-fast` target, which redirects simulator input from `/dev/null`. For an interactive console, run the simulator directly from a terminal. Use the path printed after `Verilator simulator:` by `presolve build`.

```bash
simulator=/absolute/path/to/simulator-chipyard.harness-System
cd "$(dirname "$simulator")"
./simulator-chipyard.harness-System +permissive "+loadmem=$boot_elf" \
  +max-cycles=1000000000 +permissive-off "$boot_elf"
```

Boot the baseline first, then reuse the workload for the custom experiment. The BTB replacement changes branch prediction, so the same workload is expected to work for both BOOM experiments. If your own hardware changes affect the memory map, boot path, or devices, check the generated device tree and the kernel drivers. Linux boot is outside the current Presolve acceptance coverage. [Chipyard boot process](https://chipyard.readthedocs.io/en/1.14.0/Customization/Boot-Process.html) · [Chipyard RTL simulation](https://chipyard.readthedocs.io/en/1.14.0/Simulation/Software-RTL-Simulation.html) · [Presolve acceptance tests](../tests/acceptance/README.md)

## Build custom binaries

### Linux userspace

Cross-compile for the target's RISC-V ISA and the workload's Linux ABI. Use the workload's toolchain and sysroot for dynamically linked programs so the guest has the expected dynamic loader and libraries. For a simple static C program, use the pinned toolchain's Linux compiler.

```bash
"${CHIPYARD_TOOLCHAIN_PREFIX:?Set the Chipyard toolchain prefix}/riscv-tools/bin/riscv64-unknown-linux-gnu-gcc" \
  -static -O2 hello.c -o hello
```

Add the executable to your workload with FireMarshal's `files` or `overlay` setting, for example at `/usr/bin/hello`. Rebuild the workload with the same board and `--no-disk`, then run the app at the guest shell or set FireMarshal's `command` field to run it at boot. [FireMarshal custom workloads](https://firemarshal.readthedocs.io/en/stable/Tutorials/custom.html) · [Workload options](https://firemarshal.readthedocs.io/en/stable/workloadConfig.html#files)

### Bare metal

Put the pinned toolchain's `riscv-tools/bin` on `PATH` and follow Chipyard's bare-metal guide to compile and link an HTIF ELF with `riscv64-unknown-elf-gcc`. From the resolved workspace, pass that ELF to Presolve. The branch-test example provides a standalone assembly payload with its own HTIF linker script and exit code. [Chipyard bare-metal guide](https://chipyard.readthedocs.io/en/1.14.0/Software/Baremetal.html) · [Presolve branch-test example](presolve-btb.md#run-both-systems-with-the-same-program)

```bash
presolve build baseline
presolve run baseline ./hello.riscv
```

With the pinned toolchain, Chipyard's C hello-world example printed its message in Spike and then reported `FAILED (tohost = 5)`. For a minimal smoke test, include `<unistd.h>` and replace `return 0;` with `fflush(stdout);` followed by `_exit(0);`. This version exits successfully in Spike.
