# Chipyard acceptance tests

Use **Actions → Chipyard Generator CI → Run workflow** to run the suite. The Ubuntu matrix uses the Chipyard source revision pinned by Presolve and the upstream Conda lock that supplies the reference compiler versions. CIRCT's `firtool` executable comes from the release pinned by that Chipyard revision. Matrix jobs run independently and upload their logs and available run manifests.

| Suite | Acceptance checks |
| --- | --- |
| [Rocket reference](rocket-reference.sh) | Run the upstream Rocket simulation with the reference payload. Verify the payload checksum, submodule pins, and toolchain versions. |
| Resolver and workspace checks | Run the resolver assertions, then resolve and verify independently copied local and Git workspaces. Compare lockfiles byte for byte, including after offline verification of the Git fixture. |
| [Workspace builds](workspace-builds.sh) | Run the reference payload in an external workspace. Check the local and Git counters in generated RTL before and after removing generated hardware outputs. |
| [Source selection](source-selection.sh) | Build standalone modules and the baseline and selected systems. Check recursive imports, passive integration classes, diagnostics for broken and undeclared adapters, and the baseline RTL, classpath, and lockfile after switching back with warm caches. |
| [Generator aliases](generator-aliases.sh) | Switch between aliased counter revisions and between two pinned BOOM revisions. Check the revision-specific RTL and manifest, verify that the graph, lockfile, and cached sources remain intact, and reject an experiment that imports both counter revisions. |
| [Generator simulation](generator-simulation.sh) | Use the packaged CLI from independent workspaces to simulate a whole generator and a BOOM configuration with a replacement BTB. Check functional markers, effective parameters, and the diagnostic for a host without the replacement hook. |
| [RTL integration](rtl-integration.sh) | Regenerate and simulate RTL from a separately packaged non-Chisel wrapper. Simulate two aliased BOOM revisions together and check that both harts run, expose distinct identities, and produce collision-free RTL. |

The generator simulation suite checks the elaborated counter width and custom BTB dimensions in the build's `run.json`.

## Pending interfaces

- FireSim needs a public entry point for running an experiment and reporting the functional result of a representative workload.
- Synthesis needs a public entry point for synthesizing an experiment and reporting the resulting netlist or synthesis report.
