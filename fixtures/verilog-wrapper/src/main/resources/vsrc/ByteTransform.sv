module ByteTransform(
  input  wire [7:0] inputByte,
  output wire [7:0] outputByte
);
  assign outputByte = {inputByte[3:0], inputByte[7:4]} ^ 8'hA5;
endmodule
