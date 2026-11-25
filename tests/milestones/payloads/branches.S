.section .text
.globl _start
_start:
  li t0, 128
  li t1, 0
.loop:
  addi t1, t1, 3
  addi t0, t0, -1
  bnez t0, .loop

  li t2, 384
  bne t1, t2, .fail
  li a0, 1
  j .report
.fail:
  li a0, 3
.report:
  la t0, tohost
  sd a0, 0(t0)
  j .report

.section .tohost,"aw",@progbits
.balign 64
.globl tohost
tohost: .dword 0
.balign 64
.globl fromhost
fromhost: .dword 0
