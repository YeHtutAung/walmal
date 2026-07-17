#!/usr/bin/env python3
"""Generate flat-illustration seed images for the 15 Walmal Sport products (V17).

Outputs 1200x1200 PNGs into scripts/seed-images/. Idempotent (overwrites).
Style: large simple geometric silhouettes, centered, generous margins, no text,
3-4 colors per image, soft drop-shadow ellipse. Drawn at 2400x2400 and
downsampled with LANCZOS for anti-aliasing.

Run: python scripts/generate-seed-images.py
"""

import os

from PIL import Image, ImageDraw, ImageFilter

# ---------------------------------------------------------------- palette
BG = "#f1f1ee"
RED = "#e0281b"
BLACK = "#141418"
WHITE = "#ffffff"
TEAL = "#1f6f6b"
GREEN = "#2e7d4f"
NAVY = "#22304a"
GOLD = "#e0a615"
GREY = "#d9d9d4"

# Working canvas (supersampled 2x)
S = 2400
OUT = 1200

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "seed-images")


def new_canvas():
    img = Image.new("RGB", (S, S), BG)
    return img, ImageDraw.Draw(img)


def draw_shadow(img, cx, cy, w, h, alpha=60):
    """Soft dark ellipse shadow centered at (cx, cy)."""
    layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    d.ellipse([cx - w // 2, cy - h // 2, cx + w // 2, cy + h // 2],
              fill=(20, 20, 24, alpha))
    layer = layer.filter(ImageFilter.GaussianBlur(40))
    img.paste(Image.alpha_composite(img.convert("RGBA"), layer).convert("RGB"),
              (0, 0))


def save(img, name):
    img = img.resize((OUT, OUT), Image.LANCZOS)
    path = os.path.join(OUT_DIR, name)
    img.save(path, "PNG")
    print(f"wrote {path}")


def rr(d, box, radius, fill):
    d.rounded_rectangle(box, radius=radius, fill=fill)


# ---------------------------------------------------------------- boot
def draw_boot(name, body, sole, accent, accent2=None):
    """Side-profile football boot facing left. Body silhouette + sole with
    studs + lace detail + accent stripe."""
    img, d = new_canvas()
    draw_shadow(img, 1200, 1760, 1700, 220)

    # Sole baseline y
    sole_top = 1560
    sole_bot = 1660

    # Boot upper silhouette (polygon), toe at left ~x=350, heel at right ~x=2000
    upper = [
        (350, sole_top),          # toe tip bottom
        (360, 1440),              # toe front
        (430, 1330),              # toe top curve
        (640, 1250),              # vamp rise
        (900, 1200),              # lace area start
        (1240, 1130),             # instep
        (1450, 950),              # ankle front
        (1530, 820),              # collar front
        (1900, 820),              # collar top back
        (1990, 980),              # heel curve upper
        (2050, 1250),             # heel back
        (2050, sole_top),         # heel bottom
    ]
    d.polygon(upper, fill=body)

    # Ankle collar rim (slightly darker/lighter band) using accent
    d.polygon([(1530, 820), (1900, 820), (1880, 900), (1560, 900)],
              fill=accent)

    # Sole
    d.rounded_rectangle([330, sole_top - 10, 2070, sole_bot], radius=45,
                        fill=sole)

    # Studs
    for sx in (480, 720, 960, 1420, 1680, 1900):
        d.polygon([(sx - 55, sole_bot), (sx + 55, sole_bot),
                   (sx + 30, sole_bot + 110), (sx - 30, sole_bot + 110)],
                  fill=sole)

    # Accent stripe sweeping along the side
    d.polygon([(430, 1400), (1250, 1210), (1980, 1210), (1980, 1300),
               (1270, 1300), (470, 1470)], fill=accent)
    if accent2:
        d.polygon([(470, 1480), (1275, 1312), (1980, 1312), (1980, 1350),
                   (1290, 1350), (490, 1512)], fill=accent2)

    # Lace detail: short diagonal bars across the instep
    lace = WHITE if body != WHITE and body != GREY else BLACK
    if accent == lace:
        lace = BLACK if lace == WHITE else WHITE
    for i in range(5):
        x0 = 880 + i * 130
        y0 = 1215 - i * 28
        d.rounded_rectangle([x0, y0 - 22, x0 + 105, y0 + 22], radius=22,
                            fill=lace)

    save(img, name)


# ---------------------------------------------------------------- apparel
def torso_polygon(cx, top, w, h, sleeve_drop, sleeve_out):
    """Return (body_polygon, left_sleeve, right_sleeve) point lists for a
    jersey/tee: torso with short sleeves."""
    half = w // 2
    shoulder_y = top
    hem_y = top + h
    body = [
        (cx - half + 60, shoulder_y),
        (cx + half - 60, shoulder_y),
        (cx + half, shoulder_y + 60),
        (cx + half - 30, hem_y),
        (cx - half + 30, hem_y),
        (cx - half, shoulder_y + 60),
    ]
    lsleeve = [
        (cx - half + 70, shoulder_y + 10),
        (cx - half - sleeve_out, shoulder_y + sleeve_drop),
        (cx - half - sleeve_out + 60, shoulder_y + sleeve_drop + 230),
        (cx - half + 40, shoulder_y + sleeve_drop + 120),
    ]
    rsleeve = [
        (cx + half - 70, shoulder_y + 10),
        (cx + half + sleeve_out, shoulder_y + sleeve_drop),
        (cx + half + sleeve_out - 60, shoulder_y + sleeve_drop + 230),
        (cx + half - 40, shoulder_y + sleeve_drop + 120),
    ]
    return body, lsleeve, rsleeve


def draw_shirt(name, base, trim, trim2=None, chest_mark=None, polo=False,
               simple=False):
    """Jersey / tee / polo. trim = collar+cuff color. chest_mark = small
    rectangle mark color (tee). polo adds a collar wedge."""
    img, d = new_canvas()
    draw_shadow(img, 1200, 1980, 1500, 200)

    cx, top, w, h = 1200, 560, 1160, 1360
    body, lsl, rsl = torso_polygon(cx, top, w, h, 340, 260)

    d.polygon(lsl, fill=base)
    d.polygon(rsl, fill=base)
    d.polygon(body, fill=base)

    # Sleeve cuffs (trim)
    d.polygon([(cx - w // 2 - 260, top + 340),
               (cx - w // 2 - 200, top + 570),
               (cx - w // 2 - 120, top + 520),
               (cx - w // 2 - 190, top + 300)], fill=trim)
    d.polygon([(cx + w // 2 + 260, top + 340),
               (cx + w // 2 + 200, top + 570),
               (cx + w // 2 + 120, top + 520),
               (cx + w // 2 + 190, top + 300)], fill=trim)

    if polo:
        # Polo collar: two triangular flaps + placket
        d.polygon([(cx - 260, top - 10), (cx - 40, top + 260),
                   (cx - 300, top + 170)], fill=trim)
        d.polygon([(cx + 260, top - 10), (cx + 40, top + 260),
                   (cx + 300, top + 170)], fill=trim)
        d.rounded_rectangle([cx - 35, top + 120, cx + 35, top + 480],
                            radius=25, fill=trim)
        # Neck opening
        d.polygon([(cx - 250, top - 5), (cx + 250, top - 5), (cx, top + 150)],
                  fill=base)
        d.polygon([(cx - 260, top - 10), (cx - 40, top + 260),
                   (cx - 300, top + 170)], fill=trim)
        d.polygon([(cx + 260, top - 10), (cx + 40, top + 260),
                   (cx + 300, top + 170)], fill=trim)
    else:
        # Crew collar: trim arc band at neck
        d.chord([cx - 260, top - 120, cx + 260, top + 220], 0, 180, fill=trim)
        d.chord([cx - 190, top - 110, cx + 190, top + 140], 0, 180, fill=BG)
        # re-fill inside of the neck cutout with base below the chord line
        d.rectangle([cx - 190, top + 15, cx + 190, top + 20], fill=trim)

    if not simple:
        # Hem trim band
        d.polygon([(cx - w // 2 + 30, top + h - 70),
                   (cx + w // 2 - 30, top + h - 70),
                   (cx + w // 2 - 30, top + h),
                   (cx - w // 2 + 30, top + h)], fill=trim)
        # Side stripes
        d.polygon([(cx - w // 2 + 5, top + 100), (cx - w // 2 + 75, top + 100),
                   (cx - w // 2 + 100, top + h - 80),
                   (cx - w // 2 + 32, top + h - 80)], fill=trim)
        d.polygon([(cx + w // 2 - 5, top + 100), (cx + w // 2 - 75, top + 100),
                   (cx + w // 2 - 100, top + h - 80),
                   (cx + w // 2 - 32, top + h - 80)], fill=trim)
        if trim2:
            # second trim: thin chest band
            d.rectangle([cx - w // 2 + 55, top + 430, cx + w // 2 - 55,
                         top + 500], fill=trim2)

    if chest_mark:
        d.rounded_rectangle([cx - 300, top + 330, cx - 140, top + 430],
                            radius=30, fill=chest_mark)

    save(img, name)


def draw_pants(name, base, stripe):
    """Tapered training pants: waistband + two legs, side stripe."""
    img, d = new_canvas()
    draw_shadow(img, 1200, 2020, 1300, 190)

    top = 420
    # Waistband
    d.rounded_rectangle([760, top, 1640, top + 130], radius=40, fill=stripe)
    # Drawstring dots
    d.ellipse([1160, top + 40, 1210, top + 90], fill=BG)
    d.ellipse([1230, top + 40, 1280, top + 90], fill=BG)

    hip_y = top + 130
    crotch_y = hip_y + 480
    ankle_y = 2020

    # Left leg
    d.polygon([
        (760, hip_y), (1195, hip_y), (1195, crotch_y),
        (1120, ankle_y), (830, ankle_y), (770, crotch_y),
    ], fill=base)
    # Right leg
    d.polygon([
        (1205, hip_y), (1640, hip_y), (1630, crotch_y),
        (1570, ankle_y), (1280, ankle_y), (1205, crotch_y),
    ], fill=base)
    # Crotch gusset fill
    d.polygon([(1195, hip_y), (1205, hip_y), (1205, crotch_y - 200),
               (1195, crotch_y - 200)], fill=base)

    # Ankle cuffs
    d.rectangle([830, ankle_y - 90, 1120, ankle_y], fill=stripe)
    d.rectangle([1280, ankle_y - 90, 1570, ankle_y], fill=stripe)

    # Side stripes (outer seam of each leg)
    d.polygon([(775, hip_y), (845, hip_y), (905, ankle_y - 90),
               (845, ankle_y - 90)], fill=stripe)
    d.polygon([(1555, hip_y), (1625, hip_y), (1565, ankle_y - 90),
               (1505, ankle_y - 90)], fill=stripe)

    save(img, name)


# ---------------------------------------------------------------- goal
def draw_goal(name):
    img, d = new_canvas()
    # Ground strip
    d.rectangle([0, 1850, S, 2000], fill=GREEN)
    draw_shadow(img, 1200, 1860, 1900, 160, alpha=45)

    post_w = 70
    left_x, right_x = 340, 2060
    bar_y = 620
    ground_y = 1870

    net_grey = GREY
    dark = "#b9b9b2"

    # Net: grid of lines inside the frame (behind posts)
    net_left = left_x + post_w
    net_right = right_x - post_w
    net_top = bar_y + post_w
    # slanted net back: draw verticals converging slightly
    for i in range(13):
        x = net_left + i * (net_right - net_left) // 12
        d.line([(x, net_top), (x - (x - 1200) // 6, ground_y)], fill=net_grey,
               width=10)
    for j in range(9):
        y = net_top + j * (ground_y - net_top) // 8
        shrink = (y - net_top) // 6
        d.line([(net_left + shrink, y), (net_right - shrink, y)],
               fill=net_grey, width=10)

    # Posts + crossbar (white with subtle shading edge)
    d.rectangle([left_x, bar_y, left_x + post_w, ground_y], fill=WHITE)
    d.rectangle([right_x - post_w, bar_y, right_x, ground_y], fill=WHITE)
    d.rectangle([left_x, bar_y, right_x, bar_y + post_w], fill=WHITE)
    # Shading lines on posts
    d.rectangle([left_x + post_w - 14, bar_y + post_w, left_x + post_w,
                 ground_y], fill=dark)
    d.rectangle([right_x - 14, bar_y + post_w, right_x, ground_y], fill=dark)
    d.rectangle([left_x, bar_y + post_w - 14, right_x, bar_y + post_w],
                fill=dark)

    save(img, name)


# ---------------------------------------------------------------- ball
def draw_ball(name):
    img, d = new_canvas()
    draw_shadow(img, 1200, 1950, 1300, 190)

    cx, cy, r = 1200, 1160, 780
    # Red ring accent behind the ball
    d.ellipse([cx - r - 60, cy - r - 60, cx + r + 60, cy + r + 60], fill=RED)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=WHITE)

    import math

    def pent(cx_, cy_, rad, rot=-90):
        pts = []
        for k in range(5):
            a = math.radians(rot + k * 72)
            pts.append((cx_ + rad * math.cos(a), cy_ + rad * math.sin(a)))
        return pts

    # Center pentagon
    d.polygon(pent(cx, cy, 230), fill=BLACK)
    # Ring of partial pentagons around, clipped by ball circle via mask
    ring = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    rd = ImageDraw.Draw(ring)
    for k in range(5):
        a = math.radians(-90 + k * 72)
        px = cx + 640 * math.cos(a)
        py = cy + 640 * math.sin(a)
        rd.polygon(pent(px, py, 210, rot=-90 + k * 72 + 36), fill=BLACK)
    # Mask to ball circle
    mask = Image.new("L", (S, S), 0)
    md = ImageDraw.Draw(mask)
    md.ellipse([cx - r + 8, cy - r + 8, cx + r - 8, cy + r - 8], fill=255)
    img.paste(Image.new("RGB", (S, S), BLACK), (0, 0),
              Image.composite(ring.split()[3], Image.new("L", (S, S), 0), mask))

    save(img, name)


# ---------------------------------------------------------------- socks
def draw_socks(name, base, grip):
    """Pair of L-shaped socks side by side, toes pointing right, grip dots
    on the soles."""
    img, d = new_canvas()
    draw_shadow(img, 1200, 2020, 1600, 180)

    def sock(leg_x, top, sole_y, toe_x):
        """One sock: vertical leg tube + horizontal foot to the right."""
        leg_w = 360
        foot_h = 330
        # Leg tube
        d.rounded_rectangle([leg_x, top, leg_x + leg_w, sole_y], radius=110,
                            fill=base)
        # Cuff (grip color band at top)
        d.rounded_rectangle([leg_x, top, leg_x + leg_w, top + 200],
                            radius=110, fill=grip)
        d.rectangle([leg_x, top + 110, leg_x + leg_w, top + 200], fill=grip)
        # Foot: rounded horizontal tube from heel to toe
        d.rounded_rectangle([leg_x, sole_y - foot_h, toe_x, sole_y],
                            radius=150, fill=base)
        # Fill the inner corner between leg and foot
        d.rectangle([leg_x + 40, sole_y - foot_h - 60, leg_x + leg_w,
                     sole_y - 100], fill=base)
        # Toe cap: grip-colored rounded end
        d.rounded_rectangle([toe_x - 260, sole_y - foot_h, toe_x, sole_y],
                            radius=150, fill=grip)
        d.rectangle([toe_x - 260, sole_y - foot_h, toe_x - 150, sole_y],
                    fill=base)
        # Heel patch
        d.pieslice([leg_x - 10, sole_y - 300, leg_x + 290, sole_y], 90, 180,
                   fill=grip)
        # Grip dots along the sole
        x = leg_x + 330
        while x < toe_x - 200:
            d.ellipse([x - 26, sole_y - 60, x + 26, sole_y - 8], fill=grip)
            x += 110

    # Back sock (slightly up-left), front sock (down-right)
    sock(430, 380, 1560, 1560)
    sock(900, 700, 1930, 2030)

    save(img, name)


# ---------------------------------------------------------------- shinguards
def draw_shinguards(name, shell, strap):
    """Two rounded vertical shells side by side with strap lines."""
    img, d = new_canvas()
    draw_shadow(img, 1200, 1960, 1500, 190)

    def guard(cx):
        top, bot = 460, 1900
        w = 430
        # Shell: rounded, tapered at bottom -> approximate with rounded rect
        # + narrower rounded rect overlay
        d.rounded_rectangle([cx - w // 2, top, cx + w // 2, bot - 300],
                            radius=200, fill=shell)
        d.polygon([(cx - w // 2 + 20, bot - 500), (cx + w // 2 - 20, bot - 500),
                   (cx + 140, bot - 60), (cx - 140, bot - 60)], fill=shell)
        d.rounded_rectangle([cx - 150, bot - 200, cx + 150, bot], radius=140,
                            fill=shell)
        # Center ridge highlight
        d.rounded_rectangle([cx - 45, top + 120, cx + 45, bot - 240],
                            radius=45, fill="#2a2a30")
        # Straps: two horizontal bands
        for sy in (top + 320, bot - 560):
            d.rounded_rectangle([cx - w // 2 - 90, sy, cx + w // 2 + 90,
                                 sy + 110], radius=55, fill=strap)
            # buckle notch
            d.rectangle([cx + w // 2 + 20, sy + 25, cx + w // 2 + 70,
                         sy + 85], fill=shell)

    guard(700)
    guard(1700)

    save(img, name)


# ---------------------------------------------------------------- main
def main():
    os.makedirs(OUT_DIR, exist_ok=True)

    # Boots
    draw_boot("velocity-elite-fg-boot.png", body=RED, sole=BLACK, accent=GOLD)
    draw_boot("phantom-strike-fg-boot.png", body=BLACK, sole=BLACK, accent=RED)
    draw_boot("aero-knit-speed-boot.png", body=WHITE, sole=GREY, accent=RED)
    draw_boot("velocity-pro-ag-boot.png", body=RED, sole=WHITE, accent=WHITE,
              accent2=BLACK)

    # Goal
    draw_goal("pro-match-goal.png")

    # Shirts
    draw_shirt("harbour-city-fan-tee.png", base=TEAL, trim=TEAL,
               chest_mark=WHITE, simple=True)
    draw_shirt("hc-home-jersey.png", base=TEAL, trim=WHITE)
    draw_shirt("hc-away-jersey.png", base=WHITE, trim=TEAL)
    draw_shirt("riverside-home-jersey.png", base=GREEN, trim=GOLD)
    draw_shirt("national-authentic-jersey.png", base=NAVY, trim=RED,
               trim2=WHITE)
    draw_shirt("dna-training-polo.png", base=NAVY, trim=TEAL, polo=True,
               simple=True)

    # Pants
    draw_pants("dna-training-pants.png", base=NAVY, stripe=RED)

    # Ball
    draw_ball("match-ball.png")

    # Socks
    draw_socks("grip-training-socks.png", base=BLACK, grip=RED)

    # Shinguards
    draw_shinguards("lite-carbon-shinguards.png", shell=BLACK, strap=RED)

    print("done: 15 images")


if __name__ == "__main__":
    main()
