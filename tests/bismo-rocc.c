#include <stdint.h>
#include "rocc.h"

static uint64_t read_reg(unsigned reg)
{
    uint64_t value;
    ROCC_INSTRUCTION_DSS(0, value, 0, reg, 1);
    return value;
}

static void write_reg(unsigned reg, uint64_t value)
{
    ROCC_INSTRUCTION_SS(0, value, reg, 0);
}

static uint64_t lhs[4] __attribute__((aligned(64)));
static uint64_t rhs[2] __attribute__((aligned(64)));
static volatile uint32_t result[8] __attribute__((aligned(64)));

static int multiply_one_tile(unsigned rows)
{
    const unsigned columns = 2;
    const unsigned result_count = rows * columns;

    // Each 64-bit word is a row of one bit plane. BISMO writes the
    // two result columns consecutively.
    rhs[0] = UINT64_MAX;
    rhs[1] = 15;
    for (unsigned row = 0; row < rows; ++row)
        lhs[row] = UINT64_C(3) << (2 * row);
    for (unsigned i = 0; i < result_count; ++i)
        result[i] = UINT32_MAX;
    asm volatile ("fence rw,rw" ::: "memory");

    // Describe a single tile with one 64-bit word per input row.
    write_reg(28, (uintptr_t)lhs);
    write_reg(29, (uintptr_t)rhs);
    write_reg(30, 8);                              // fetch block stride
    write_reg(31, 8);                              // fetch block size
    write_reg(32, rows);                           // fetch block count
    write_reg(33, 1);                              // tiles per row
    write_reg(34, 1);                              // execution tiles
    write_reg(37, (uintptr_t)result);
    write_reg(38, rows * sizeof(result[0]));        // result column stride
    write_reg(39, result_count * sizeof(result[0])); // completion byte count
    for (unsigned reg = 40; reg <= 46; ++reg)
        write_reg(reg, 1);                         // one tile at each loop level
    write_reg(47, rows * sizeof(lhs[0]));
    write_reg(48, columns * sizeof(rhs[0]));
    write_reg(49, rows);
    write_reg(50, 8);                              // common dimension in bytes

    write_reg(2, 1);
    write_reg(3, 1);
    write_reg(4, 1);
    write_reg(27, 1);

    for (unsigned attempt = 0; attempt < 10000; ++attempt) {
        if (read_reg(26) && !read_reg(5) && !read_reg(6) && !read_reg(7) &&
            result[result_count - 1] != UINT32_MAX) {
            for (unsigned row = 0; row < rows; ++row)
                if (result[row] != 2 || result[rows + row] != (row < 2 ? 2 : 0))
                    return 5;
            return 0;
        }
    }
    return 6;
}

int main(void)
{
    write_reg(0, 1);
    const uint64_t rows = read_reg(10);
    if (read_reg(1) == 0 || (rows != 2 && rows != 4) ||
        read_reg(11) != 2 || read_reg(12) != 64 || read_reg(26) != 1)
        return 1;

    write_reg(50, 8);
    if (read_reg(50) != 8 || read_reg(114) != 0)
        return 2;

    write_reg(19, 1);
    const uint64_t before = read_reg(18);
    for (unsigned i = 0; i < 64; ++i)
        asm volatile ("nop");
    if (read_reg(18) <= before)
        return 3;

    write_reg(19, 0);
    const uint64_t stopped = read_reg(18);
    if (read_reg(18) != stopped)
        return 4;

    return multiply_one_tile(rows);
}
