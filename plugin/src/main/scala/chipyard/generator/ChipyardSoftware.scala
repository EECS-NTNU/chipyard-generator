package chipyard.generator

import java.io.{File, IOException}
import java.nio.file.{Files, Path, Paths}
import scala.sys.process.{Process, ProcessLogger}

private object ChipyardSoftware {
  /** Initializes upstream FireMarshal submodules in the selected toolchain's checkout. */
  def setup(toolchain: Path, revision: String, offline: Boolean, info: String => Unit, warn: String => Unit): Path = {
    if (!revision.matches("[0-9a-fA-F]{40}"))
      throw new ResolutionException("Set chipyardVersion to a full Chipyard commit before setting up target software.")

    val prefix = toolchain.toAbsolutePath.normalize()
    val checkout = Option(prefix.getParent).filter(path => Files.exists(path.resolve(".git"))).getOrElse(
      throw new ResolutionException(s"FireMarshal setup needs a writable Chipyard checkout containing $prefix. " +
        "Use the native Chipyard installation configured by cli/setup.sh."))
    if (!Files.isWritable(checkout))
      throw new ResolutionException(s"FireMarshal setup needs a writable Chipyard checkout: $checkout")

    val riscv = prefix.resolve("riscv-tools")
    val python = prefix.resolve("bin/python3")
    val compiler = riscv.resolve("bin/riscv64-unknown-linux-gnu-gcc")
    Seq(python, compiler).foreach { executable =>
      if (!Files.isExecutable(executable))
        throw new ResolutionException(s"Missing $executable. Use a full Chipyard Conda toolchain for FireMarshal.")
    }
    val path = Seq(prefix.resolve("bin").toString, riscv.resolve("bin").toString, sys.env.getOrElse("PATH", ""))
      .mkString(File.pathSeparator)
    val environment = Seq("PATH" -> path, "RISCV" -> riscv.toString, "CONDA_PREFIX" -> prefix.toString)
    val gitEnvironment = if (offline) environment :+ ("GIT_ALLOW_PROTOCOL" -> "") else environment
    val quiet = ProcessLogger(_ => ())
    val head = try Process(Seq("git", "rev-parse", "--verify", "HEAD"), checkout.toFile, environment: _*).!!(quiet).trim
    catch {
      case error @ (_: RuntimeException | _: IOException) =>
        throw new ResolutionException(s"Could not read the Chipyard revision at $checkout: ${error.getMessage}")
    }
    if (!head.equalsIgnoreCase(revision))
      throw new ResolutionException(s"Chipyard checkout $checkout is at $head. The workspace requires $revision.")

    val marshal = checkout.resolve("software/firemarshal")
    val offlineHint = if (offline) " FireMarshal sources must already be available in offline mode." else ""
    def run(command: Seq[String], directory: Path, settings: Seq[(String, String)], failure: String): Unit = {
      val status = try Process(command, directory.toFile, settings: _*).!(ProcessLogger(info, warn))
      catch {
        case error: IOException => throw new ResolutionException(s"$failure ${error.getMessage}")
      }
      if (status != 0) throw new ResolutionException(s"$failure (exit $status).")
    }

    run(Seq("git", "submodule", "update", "--init", "--checkout", "--", "software/firemarshal"), checkout,
      gitEnvironment, s"Could not initialize FireMarshal.$offlineHint")
    run(Seq(python.toString, "-B", "-c", "import wlutil"), marshal, environment,
      "FireMarshal's Python dependencies are unavailable. Use a full Chipyard Conda toolchain.")
    run(Seq("bash", "./init-submodules.sh"), marshal, gitEnvironment,
      s"Could not initialize FireMarshal dependencies.$offlineHint")

    val searchPath = path.split(File.pathSeparator)
    val missing = Seq("guestmount", "guestunmount").filterNot { tool =>
      searchPath.exists(directory => Files.isExecutable(Paths.get(directory).resolve(tool)))
    }
    if (missing.nonEmpty)
      warn(s"Missing host tools for FireMarshal's unprivileged disk image path: ${missing.mkString(", ")}.")
    info(s"Chipyard Conda environment: $prefix")
    info(s"FireMarshal directory: $marshal")
    marshal
  }
}
