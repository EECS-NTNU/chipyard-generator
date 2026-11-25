# Chipyard Generator

The Milestone 1 sbt plugin resolves a hardware workspace from its `build.sbt`. It fetches pinned Git sources, fingerprints local source directories, and records one dependency graph for the workspace.

## Try it locally

With Git, a JDK (11 or newer), and sbt installed, publish the plugin and resolver to your local Ivy repository.

```sh
sbt publishLocal
sbt 'resolver/Test/runMain chipyard.generator.MilestoneOneChecks'
```

Copy the two fixture workspaces outside the tool checkout. Each pins the same locally published plugin version.

```sh
workspaces=$(mktemp -d)
cp -R fixtures/local fixtures/git "$workspaces/"
(cd "$workspaces/local" && sbt hardwareResolve hardwareResolve hardwareVerify)
(cd "$workspaces/git" && ./prepare.sh && sbt hardwareResolve hardwareResolve hardwareVerify)
(cd "$workspaces/git" && sbt 'set hardwareOffline := true' hardwareVerify)
```

Each workspace writes `hardware.lock` and a generated `target/hardware/<fingerprint>/plan.json`. The plan includes the checkout directories and dependency edges. `hardwareVerify` uses the same declarations and reports drift without rewriting the lock. Run `hardwareResolve` again to accept intentional changes to a declaration or local source.

## Workspace declarations

Add `addSbtPlugin("org.eecs-ntnu" % "sbt-chipyard-generator" % "0.1.0-SNAPSHOT")` to `project/plugins.sbt` and enable `ChipyardPlugin` in the workspace root's `build.sbt`.

```scala
enablePlugins(ChipyardPlugin)

chipyardVersion := "a7946ede9bd625204435715dc40c750e2f8a251f"

hardwareDependencies := Seq(
  Generator.chipyard("boom"),
  Generator.local("custom-cache", baseDirectory.value / "cache-generator")
)
```

Leave `chipyardVersion` unset for a platform-independent workspace. The bundled Chipyard recipe covers the source graph for BOOM and its dependencies at the pinned revision. The recipe records the upstream toolchain configuration. The reference elaboration and simulation are separate milestones.

Use `Generator.git(name, url, ref, alias = "...")` for an independent Git dependency. Supply a full 40-character commit ID. An alias identifies a graph node and defaults to the generator name. Use distinct aliases to resolve different revisions. For an independent source with explicit dependency requirements, use `Generator.git(...).requires(otherGenerator)`. A diamond shares a single node when both paths request the same dependency identity and edges.

Git sources default to `$XDG_CACHE_HOME/hardware-generators` or `~/.cache/hardware-generators`. Override the location with `hardwareCacheDirectory`. Set `hardwareOffline := true` to require prefetched Git sources. The cache publishes source trees by repository and commit. For a direct Git dependency, nested submodule checkouts are stored as separate entries and their locations appear in the plan. A Chipyard workspace fetches the platform tree and the generator repositories selected by its recipe.

Local source fingerprints include file contents and executable bits. The resolver skips Git metadata, standard sbt output directories at the source root and under `project/`, and the source root's `hardware.lock`. Symbolic links must resolve to included inputs inside the same local source directory.
