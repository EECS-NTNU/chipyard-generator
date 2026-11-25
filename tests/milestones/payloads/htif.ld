ENTRY(_start)

PHDRS
{
  code PT_LOAD FLAGS(5);
  writable PT_LOAD FLAGS(6);
}

SECTIONS
{
  . = 0x80000000;
  .text : { *(.text .text.*) } :code
  . = ALIGN(64);
  .tohost : { *(.tohost) } :writable
  . = ALIGN(64);
  .data : { *(.data .data.*) } :writable
}
