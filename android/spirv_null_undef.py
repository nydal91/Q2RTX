#!/usr/bin/env python3
"""Replace module-scope OpUndef with OpConstantNull in a SPIR-V binary.

spirv-opt turns reads of variables the GLSL never initialized into OpUndef. Desktop
drivers tend to produce zeros there; mobile drivers may produce garbage, which shows
up as black or flickering surfaces. Zero is what the unoptimized shader effectively
gets on the desktop, so make it explicit.

usage: spirv_null_undef.py in.spv out.spv
"""
import struct
import sys

OP_UNDEF = 1
OP_CONSTANT_NULL = 46
OP_FUNCTION = 54


def main(src, dst):
    data = open(src, 'rb').read()
    words = list(struct.unpack('<%dI' % (len(data) // 4), data))
    if words[0] != 0x07230203:
        sys.exit('%s: not a SPIR-V module' % src)

    i, replaced = 5, 0
    while i < len(words):
        opcode, count = words[i] & 0xffff, words[i] >> 16
        if count == 0:
            sys.exit('%s: malformed instruction at word %d' % (src, i))
        if opcode == OP_FUNCTION:
            break  # module-scope section ends at the first function
        if opcode == OP_UNDEF:
            words[i] = (count << 16) | OP_CONSTANT_NULL
            replaced += 1
        i += count

    open(dst, 'wb').write(struct.pack('<%dI' % len(words), *words))
    print('%s: replaced %d OpUndef' % (dst, replaced))


if __name__ == '__main__':
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
