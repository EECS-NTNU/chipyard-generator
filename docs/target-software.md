# Build and run target software with Presolve

Build target software with the standard Chipyard tools. Use the Chipyard revision in your workspace's `chipyardVersion` setting and its pinned toolchain. Presolve builds the Verilator simulator for the experiment you select and runs a RISC-V ELF on it.

## Enable FireMarshal in your installed Chipyard checkout

From your Presolve workspace, run:

```bash
presolve software setup
```

The command initializes the pinned FireMarshal submodules in the writable Chipyard checkout that contains your configured toolchain. It uses the same toolchain selection as `presolve build` and prints the Conda environment and FireMarshal directory. Run this once for an installation, or again to finish an interrupted initialization. The cross-compilers and Spike are already installed with the full Chipyard toolchain.

Open an interactive Bash session with the workspace's Chipyard toolchain. This also works from a workspace subdirectory and starts Bash at the workspace root.

```bash
presolve software shell
```

The shell activates the selected Conda environment and makes the cross-compilers and Spike available. When FireMarshal is present, it also puts `marshal` on `PATH` and exports `FIREMARSHAL_DIR` and `MARSHAL_BOARD_DIR` for the Chipyard board. Run `exit` to return to your original shell. For bare-metal work, you can open the shell as soon as the Chipyard toolchain is installed.

The setup command runs on the host. The runtime container can consume ELF files from the mounted workspace. FireMarshal may need host tools such as `guestmount` and `guestunmount` when running without sudo. [FireMarshal setup](https://github.com/firesim/FireMarshal/blob/61fae80f49b97366254720f3b07ee8f8da762799/README.md#setup)

## Build a Linux boot image

Follow the FireMarshal quick start to build and boot the stock Buildroot workload in QEMU. For a custom kernel, inherit from `br-base.json` and set `linux.config` for kernel configuration fragments or `linux.source` for a different kernel tree. [Chipyard FireMarshal guide](https://chipyard.readthedocs.io/en/1.14.0/Software/FireMarshal.html) · [FireMarshal quick start](https://firemarshal.readthedocs.io/en/stable/Tutorials/quickstart.html) · [Kernel settings](https://firemarshal.readthedocs.io/en/stable/workloadConfig.html#linux)

Use `--no-disk` to bundle OpenSBI, Linux, and the root filesystem in one boot ELF for Presolve's Verilator flow. The stock build writes `images/chipyard/br-base/br-base-bin-nodisk` under the FireMarshal directory. [FireMarshal command reference](https://firemarshal.readthedocs.io/en/stable/commands.html)

```bash
(cd "$FIREMARSHAL_DIR" && marshal --no-disk build br-base.json)
```

## Boot Linux on a generated system

From the resolved Presolve workspace, build the baseline simulator and pass it the boot ELF. For a container run, put the ELF in the mounted workspace so the simulator can access it.

```bash
boot_elf="$FIREMARSHAL_DIR/images/chipyard/br-base/br-base-bin-nodisk"
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

Cross-compile for the target's RISC-V ISA and the workload's Linux ABI. Use the workload's toolchain and sysroot for dynamically linked programs so the guest has the expected dynamic loader and libraries. For a simple static C program, use the pinned toolchain's Linux compiler from the workspace.

```bash
mkdir -p hello-workload
riscv64-unknown-linux-gnu-gcc -static -O2 hello.c -o hello-workload/hello
```

Save this as `hello-workload.json` next to the `hello-workload` directory. FireMarshal copies the executable into the root filesystem and runs it at boot. [FireMarshal custom workloads](https://firemarshal.readthedocs.io/en/stable/Tutorials/custom.html) · [Workload options](https://firemarshal.readthedocs.io/en/stable/workloadConfig.html#files)

```json
{
  "name": "hello-workload",
  "base": "br-base.json",
  "files": [["hello", "/usr/bin/hello"]],
  "command": "/usr/bin/hello"
}
```

```bash
workload="$PWD/hello-workload.json"
(cd "$FIREMARSHAL_DIR" && marshal --no-disk build "$workload")
boot_elf="$FIREMARSHAL_DIR/images/chipyard/hello-workload/hello-workload-bin-nodisk"
LOADMEM=1 timeout_cycles=1000000000 presolve run baseline "$boot_elf"
```

For a separate root filesystem image, use `./marshal build "$workload"` in the FireMarshal directory. This produces `hello-workload.img` and `hello-workload-bin` in the same output directory. Test the pair in QEMU with `./marshal launch "$workload"`, or use it with another platform configured to attach a disk image. Presolve's Verilator command takes the single `*-bin-nodisk` ELF. [FireMarshal build and launch commands](https://firemarshal.readthedocs.io/en/stable/commands.html#build)

### Bare metal

For bare-metal work, activate the pinned toolchain. Follow Chipyard's bare-metal guide to compile and link an HTIF ELF with `riscv64-unknown-elf-gcc`. Spike can smoke-test the ELF before you pass it to Presolve. The branch-test example provides a standalone assembly payload with its own HTIF linker script and exit code. [Chipyard bare-metal guide](https://chipyard.readthedocs.io/en/1.14.0/Software/Baremetal.html) · [Presolve branch-test example](presolve-btb.md#run-both-systems-with-the-same-program)

```bash
presolve build baseline
spike ./hello.riscv
presolve run baseline ./hello.riscv
```

With the pinned toolchain, Chipyard's C hello-world example printed its message in Spike and then reported `FAILED (tohost = 5)`. For a minimal smoke test, include `<unistd.h>` and replace `return 0;` with `fflush(stdout);` followed by `_exit(0);`. This version exits successfully in Spike.
