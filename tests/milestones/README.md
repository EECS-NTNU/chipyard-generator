# Milestone acceptance tests

Use **Actions → Chipyard Generator CI → Run workflow** to run the suite. The Ubuntu matrix uses the Chipyard source revision pinned by Presolve and the upstream Conda lock that supplies the M0 reference compiler versions. CIRCT's `firtool` executable comes from the release pinned by that Chipyard revision. Matrix jobs run independently, and missing capabilities fail their checks. Each integration job uploads its logs and available run manifests as an evidence artifact.

| Milestone | Acceptance checks |
| --- | --- |
| M0 | Run the upstream Rocket simulation with the reference payload. Verify the payload checksum, submodule pins, and toolchain versions. |
| M1 | Run the resolver assertions, then resolve and verify independently copied local and Git workspaces. Compare lockfiles byte for byte, including after offline verification of the Git fixture. |
| M2 | Run the reference payload in an external workspace. Check the local and Git counters in generated RTL before and after removing generated hardware outputs. |
| M3 | Build standalone modules and the baseline and selected systems. Check recursive imports, passive integration classes, diagnostics for broken and undeclared adapters, and the baseline RTL, classpath, and lockfile after switching back with warm caches. |
| M4 | Switch between aliased counter revisions and between two pinned BOOM revisions. Check the revision-specific RTL and manifest, verify that the graph, lockfile, and cached sources remain intact, and reject an experiment that imports both counter revisions. |
| M5 | Use the packaged CLI from independent workspaces to simulate a whole generator and a BOOM configuration with a replacement BTB. Check functional markers, effective parameters, and the diagnostic for a host without the replacement hook. |
| M6 | Regenerate and simulate RTL from a separately packaged non-Chisel wrapper. Simulate two aliased BOOM revisions together and check that both harts run, expose distinct identities, and produce collision-free RTL. |

M4 supports one revision of each generator in an experiment. The M6 mixed-revision test exercises simultaneous use of two revisions in the same SoC. The M5 manifest checks require effective parameters in `run.json`, which the current schema lacks.

## Pending interfaces

- **M6 FireSim** needs a public entry point for running an experiment and reporting the functional result of a representative workload.
- **M6 synthesis** needs a public entry point for synthesizing an experiment and reporting the resulting netlist or synthesis report.
