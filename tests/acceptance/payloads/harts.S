.section .text
.globl _start
_start:
  csrr t0, mhartid
  beqz t0, .hart0
  li t1, 1
  bne t0, t1, .fail

  li t1, 128
  li t2, 0
.hart1_loop:
  addi t2, t2, 7
  addi t1, t1, -1
  bnez t1, .hart1_loop
  li t1, 896
  bne t1, t2, .fail
  la t0, hart1_result
  sd t2, 0(t0)
.hart1_wait:
  j .hart1_wait

.hart0:
  li t1, 128
  li t2, 0
.hart0_loop:
  addi t2, t2, 3
  addi t1, t1, -1
  bnez t1, .hart0_loop
  li t1, 384
  bne t1, t2, .fail
  la t0, hart1_result
  li t1, 896
.wait_for_hart1:
  ld t2, 0(t0)
  beqz t2, .wait_for_hart1
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

.section .data
.balign 64
hart1_result: .dword 0
