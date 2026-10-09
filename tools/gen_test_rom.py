#!/usr/bin/env python3
"""Generate Atari TV's self-made 2K test ROM (app/src/main/assets/test_cart.bin).

Pure Python 3, no assembler dependency. Hand-encoded 6502 for a 2K cart
mapped at $F800-$FFFF, entry at $F800.

What it does (NTSC, 262 scanlines: 3 VSYNC + 37 VBLANK + 192 kernel + 30 overscan):
  * 6 horizontal color bands (COLUBK change per 32 scanlines)
  * one 8px white square (player 0, GRP0=$FF) bouncing around the screen
  * joystick 0 (SWCHA bits 4-7, active low) nudges the square;
    fire (INPT4 bit 7, active low) recenters it

Bit/pixel facts below were verified against the pinned stella2023 core source
(src/emucore/Control.cxx, M6532.cxx, tia/Player.cxx, tia/LatchedInput.cxx):
  * SWCHA = (leftController.read() << 4) | rightController.read(),
    read() bit0=Up, bit1=Down, bit2=Left, bit3=Right, 0 = pressed
    => joy0: $10=Up, $20=Down, $40=Left, $80=Right
  * INPT4 bit7: 0 = fire pressed, 1 = released
  * RESP0 strobed while the beam is at visible pixel P puts player 0's left
    edge at pixel (P+5) mod 160  (Player::resp/getPosition in the core).
    The positioning loop below strobes RESP0 at 6502 cycle 5k+10 after WSYNC
    (LDX zp=3, LDA #=2, STA zp=3, k x (DEX/BNE)=5k-1, STA zp=3;
    beam color clock ~= 3 x cycle), giving pixel ~= 15k-33 for k in 3..12
    (pixels 12..147, monotonic, 8px square always fully on-screen;
    +/-3px poke-timing uncertainty is irrelevant for a test ROM).

Validation (asserted below): size == 2048, vectors at 0x7FA/0x7FC/0x7FE == 00 F8.
SPDX-License-Identifier: GPL-3.0-or-later
"""
import os
import sys

BASE = 0xF800          # cart base address
SIZE = 2048

# TIA write registers (zero page)
VSYNC, VBLANK, WSYNC = 0x00, 0x01, 0x02
NUSIZ0, COLUP0, COLUBK = 0x04, 0x06, 0x09
CTRLPF, REFP0 = 0x0A, 0x0B
PF0, PF1, PF2 = 0x0D, 0x0E, 0x0F
RESP0, GRP0, GRP1 = 0x10, 0x1B, 0x1C
ENABL, HMP0, VDELP0 = 0x1F, 0x20, 0x25
# RIOT (absolute)
SWCHA, INTIM, TIM64T, INPT4 = 0x0280, 0x0284, 0x0296, 0x038C

# zero-page game state
BALLK, BALLY, VELX, VELY, FRAME = 0x80, 0x81, 0x82, 0x83, 0x84


class Asm:
    """Tiny single-pass assembler with label fixups (branches + JMP)."""

    def __init__(self, base):
        self.base = base
        self.buf = bytearray()
        self.labels = {}
        self.fixups = []  # (kind, offset, label)

    def here(self):
        return self.base + len(self.buf)

    def label(self, name):
        self.labels[name] = self.here()

    def b(self, *bs):
        self.buf.extend(bs)

    def br(self, op, label):
        self.b(op, 0x00)
        self.fixups.append(("rel", len(self.buf) - 1, label))

    def jmp(self, label):
        self.b(0x4C, 0x00, 0x00)
        self.fixups.append(("abs", len(self.buf) - 2, label))

    def resolve(self):
        for kind, off, name in self.fixups:
            target = self.labels[name]
            if kind == "rel":
                pc = self.base + off + 1
                delta = target - pc
                if not -128 <= delta <= 127:
                    raise ValueError(f"branch {name} out of range: {delta}")
                self.buf[off] = delta & 0xFF
            else:
                self.buf[off] = target & 0xFF
                self.buf[off + 1] = (target >> 8) & 0xFF


def build() -> bytearray:
    a = Asm(BASE)
    b = a.b

    # ---- init ----
    b(0x78)                    # SEI
    b(0xD8)                    # CLD
    b(0xA2, 0xFF)              # LDX #$FF
    b(0x9A)                    # TXS
    b(0xA9, 0x00)              # LDA #0
    b(0xA2, 0x7F)              # LDX #$7F
    a.label("clr")
    b(0x95, 0x80)              # STA $80,X   (clear $80-$FF)
    b(0xCA)                    # DEX
    a.br(0x10, "clr")          # BPL clr
    # initial state: ballK=7 (~pixel 69), ballY=90, vel +1/+2
    b(0xA9, 0x07, 0x85, BALLK)  # LDA #7 / STA BALLK
    b(0xA9, 0x5A, 0x85, BALLY)  # LDA #90 / STA BALLY
    b(0xA9, 0x01, 0x85, VELX)   # LDA #1 / STA VELX
    b(0xA9, 0x02, 0x85, VELY)   # LDA #2 / STA VELY
    # TIA: white player, single copy, no playfield/missiles/ball
    b(0xA9, 0x0E, 0x85, COLUP0)  # COLUP0 = white
    b(0xA9, 0x00)
    for reg in (NUSIZ0, REFP0, CTRLPF, PF0, PF1, PF2,
                GRP0, GRP1, ENABL, VDELP0, HMP0):
        b(0x85, reg)           # STA reg

    # ---- frame loop ----
    a.label("frame")
    b(0xA9, 0x02, 0x85, VSYNC)  # VSYNC on
    b(0x85, WSYNC, 0x85, WSYNC, 0x85, WSYNC)  # 3 sync lines
    b(0xA9, 0x00, 0x85, VSYNC)  # VSYNC off
    b(0xA9, 0x02, 0x85, VBLANK)  # VBLANK on
    b(0xA9, 0x2C)              # LDA #44
    b(0x8D, TIM64T & 0xFF, TIM64T >> 8)  # STA TIM64T (44*64/76 ~= 37 lines)

    # physics every 8th frame
    b(0xE6, FRAME)             # INC FRAME
    b(0xA5, FRAME)             # LDA FRAME
    b(0x29, 0x07)              # AND #7
    a.br(0xD0, "skipPhys")     # BNE skipPhys
    # X: BALLK in 3..12, VELX = $01/$FF
    b(0xA5, BALLK, 0x18, 0x65, VELX)  # LDA BALLK / CLC / ADC VELX
    b(0xC9, 0x0D)              # CMP #13
    a.br(0xB0, "flipX")        # BCS flipX
    b(0xC9, 0x03)              # CMP #3
    a.br(0xB0, "xok")          # BCS xok
    a.label("flipX")
    b(0xA5, VELX, 0x49, 0xFE, 0x85, VELX)  # EOR #$FE flips $01<->$FF
    b(0xA5, BALLK, 0x18, 0x65, VELX)
    a.label("xok")
    b(0x85, BALLK)             # STA BALLK
    # Y: BALLY in 0..184, VELY = $02/$FE
    b(0xA5, BALLY, 0x18, 0x65, VELY)
    b(0xC9, 0xB9)              # CMP #185
    a.br(0x90, "yok")          # BCC yok
    b(0xA5, VELY, 0x49, 0xFC, 0x85, VELY)  # EOR #$FC flips $02<->$FE
    b(0xA5, BALLY, 0x18, 0x65, VELY)
    a.label("yok")
    b(0x85, BALLY)
    a.label("skipPhys")

    # input: SWCHA joy0 bits (0=pressed): $10=Up $20=Down $40=Left $80=Right
    # Up -> BALLY+2 (clamp 184)
    b(0xAD, SWCHA & 0xFF, SWCHA >> 8)  # LDA SWCHA
    b(0x29, 0x10)              # AND #$10
    a.br(0xD0, "noUp")         # BNE noUp
    b(0xA5, BALLY, 0x18, 0x69, 0x02)  # LDA/CL C/ADC #2
    b(0xC9, 0xB9)              # CMP #185
    a.br(0x90, "upok")         # BCC upok
    b(0xA9, 0xB8)              # LDA #184
    a.label("upok")
    b(0x85, BALLY)
    a.label("noUp")
    # Down -> BALLY-2 (clamp 0)
    b(0xAD, SWCHA & 0xFF, SWCHA >> 8)
    b(0x29, 0x20)
    a.br(0xD0, "noDown")
    b(0xA5, BALLY, 0x38, 0xE9, 0x02)  # LDA/SEC/SBC #2
    a.br(0xB0, "downok")       # BCS downok (no borrow)
    b(0xA9, 0x00)
    a.label("downok")
    b(0x85, BALLY)
    a.label("noDown")
    # Left -> BALLK-1 (clamp 3)
    b(0xAD, SWCHA & 0xFF, SWCHA >> 8)
    b(0x29, 0x40)
    a.br(0xD0, "noLeft")
    b(0xA5, BALLK, 0xC9, 0x03)  # LDA BALLK / CMP #3
    a.br(0xF0, "noLeft")       # BEQ noLeft (at min)
    b(0xC6, BALLK)             # DEC BALLK
    a.label("noLeft")
    # Right -> BALLK+1 (clamp 12)
    b(0xAD, SWCHA & 0xFF, SWCHA >> 8)
    b(0x29, 0x80)
    a.br(0xD0, "noRight")
    b(0xA5, BALLK, 0xC9, 0x0C)  # CMP #12
    a.br(0xB0, "noRight")       # BCS noRight (at max)
    b(0xE6, BALLK)             # INC BALLK
    a.label("noRight")
    # Fire (INPT4 bit7, 0=pressed) -> recenter
    b(0xAD, INPT4 & 0xFF, INPT4 >> 8)  # LDA INPT4
    b(0x29, 0x80)              # AND #$80
    a.br(0xD0, "noFire")       # BNE noFire
    b(0xA9, 0x07, 0x85, BALLK)
    b(0xA9, 0x5A, 0x85, BALLY)
    a.label("noFire")

    # position player 0: strobe RESP0 at cycle 5k+10 after WSYNC
    # (k=BALLK in 3..12) -> pixel ~= 15k-33 (12..147)
    b(0x85, WSYNC)             # STA WSYNC (value irrelevant)
    b(0xA6, BALLK)             # LDX BALLK
    b(0xA9, 0x00, 0x85, HMP0)  # LDA #0 / STA HMP0
    a.label("ploop")
    b(0xCA)                    # DEX
    a.br(0xD0, "ploop")        # BNE ploop
    b(0x85, RESP0)             # STA RESP0

    # wait out the VBLANK timer
    a.label("wtimer")
    b(0xAD, INTIM & 0xFF, INTIM >> 8)  # LDA INTIM
    a.br(0xD0, "wtimer")       # BNE wtimer
    b(0xA9, 0x00, 0x85, VBLANK)  # VBLANK off

    # kernel: 192 scanlines, 6 bands of 32, square via GRP0
    b(0xA0, 0xBF)              # LDY #191
    a.label("kern")
    b(0x85, WSYNC)             # STA WSYNC
    b(0x98)                    # TYA
    b(0x4A, 0x4A, 0x4A, 0x4A, 0x4A)  # LSR x5 -> Y>>5 (band 0..5)
    b(0xAA)                    # TAX
    # LDA colors,X (absolute indexed; address patched below)
    colors_op = len(a.buf)
    b(0xBD, 0x00, 0x00)
    b(0x85, COLUBK)            # STA COLUBK
    b(0x98, 0x38)              # TYA / SEC
    b(0xE5, BALLY)             # SBC BALLY
    b(0xC9, 0x08)              # CMP #8
    a.br(0xB0, "nodraw")       # BCS nodraw
    b(0xA9, 0xFF)              # LDA #$FF
    b(0x2C)                    # BIT <next 2 bytes as addr> (skips LDA #0)
    a.label("nodraw")
    b(0xA9, 0x00)              # LDA #0
    b(0x85, GRP0)              # STA GRP0
    b(0x88)                    # DEY
    b(0xC0, 0xFF)              # CPY #$FF (192 iterations: Y=191..0; BPL would
    a.br(0xD0, "kern")         # BNE kern   quit early since Y>=128 sets N)

    # overscan: 30 lines
    b(0xA9, 0x02, 0x85, VBLANK)  # VBLANK on
    b(0xA2, 0x1E)              # LDX #30
    a.label("osloop")
    b(0x85, WSYNC)
    b(0xCA)                    # DEX
    a.br(0xD0, "osloop")       # BNE osloop
    a.jmp("frame")

    # color table: 6 NTSC hues, distinct
    colors_addr = a.here()
    a.b(0x24, 0x64, 0x84, 0xA4, 0xC4, 0xE6)
    # patch the LDA colors,X operand
    a.buf[colors_op + 1] = colors_addr & 0xFF
    a.buf[colors_op + 2] = (colors_addr >> 8) & 0xFF

    a.resolve()
    rom = bytearray(a.buf)
    if len(rom) > SIZE - 6:
        raise ValueError(f"code too big: {len(rom)}")
    rom.extend(b"\x00" * (SIZE - 6 - len(rom)))
    # vectors: NMI/RESET/IRQ -> $F800
    rom.extend(b"\x00\xF8\x00\xF8\x00\xF8")
    assert len(rom) == SIZE
    return rom


def main():
    rom = build()
    assert len(rom) == 2048, len(rom)
    assert rom[0x7FA:0x800] == b"\x00\xF8" * 3, rom[0x7FA:0x800].hex()
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "..", "app", "src", "main", "assets", "test_cart.bin")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "wb") as f:
        f.write(rom)
    print(f"wrote {out} ({len(rom)} bytes)")
    print(f"vectors @0x7FA: {rom[0x7FA:0x800].hex(' ')}")
    # quick disassembly sanity: first bytes should be SEI CLD LDX #$FF TXS
    assert rom[0:5] == bytes([0x78, 0xD8, 0xA2, 0xFF, 0x9A]), rom[0:5].hex()


if __name__ == "__main__":
    sys.exit(main())
