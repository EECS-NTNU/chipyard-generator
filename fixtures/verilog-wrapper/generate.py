import sys
from pathlib import Path


def generate_rtl() -> str:
    """Emit the wrapper's combinational byte transform as SystemVerilog."""
    return """module ByteTransform(
  input  wire [7:0] inputByte,
  output wire [7:0] outputByte
);
  assign outputByte = {inputByte[3:0], inputByte[7:4]} ^ 8'hA5;
endmodule
"""


if __name__ == "__main__":
    Path(sys.argv[1]).write_text(generate_rtl(), encoding="utf-8")
