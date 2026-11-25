import re
import sys
from collections import defaultdict
from pathlib import Path


def check_rtl(filelist: Path) -> None:
    """Check the final simulator file list for unique RTL module definitions."""
    modules: dict[str, list[Path]] = defaultdict(list)
    declaration = re.compile(r"^\s*module\s+([\w$]+)", re.MULTILINE)
    for entry in filelist.read_text().splitlines():
        source = filelist.parent / entry
        if source.suffix not in {".sv", ".v"}:
            continue
        if not source.is_file():
            source = filelist.parent / "gen-collateral" / entry
        for name in declaration.findall(source.read_text()):
            modules[name].append(source)

    collisions = {name: paths for name, paths in modules.items() if len(paths) > 1}
    if collisions:
        raise SystemExit(f"Multiple definitions in simulator RTL: {collisions}")
    boom_tiles = [name for name in modules if re.fullmatch(r"BoomTile(?:_\d+)?", name)]
    tile_pmus = [name for name in modules if re.fullmatch(r"TilePMU(?:_\d+)?", name)]
    if len(boom_tiles) != 2 or len(tile_pmus) != 1:
        raise SystemExit(f"Expected distinct BOOM tile definitions and one PMU, found {boom_tiles}, {tile_pmus}")


if __name__ == "__main__":
    check_rtl(Path(sys.argv[1]))
