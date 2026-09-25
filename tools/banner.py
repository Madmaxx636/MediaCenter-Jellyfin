import re, math
from PIL import Image, ImageDraw, ImageFilter, ImageFont

# The Jellyfin mark (jellyfin-ux, CC BY-SA 4.0), 512-unit viewBox.
INNER = "M190.56 329.07c8.63 17.3 122.4 17.12 130.93 0 8.52-17.1-47.9-119.78-65.46-119.8-17.57 0-74.1 102.5-65.47 119.8z"
OUTER = ("M58.75 417.03c25.97 52.15 368.86 51.55 394.55 0S308.93 56.08 256.03 56.08c-52.92 0-223.25 308.8-197.28 360.95z"
         "m68.04-45.25c-17.02-34.17 94.6-236.5 129.26-236.5 34.67 0 146.1 202.7 129.26 236.5-16.83 33.8-241.5 34.17-258.52 0z")

def polys(d, steps=24):
    toks = re.findall(r"[A-Za-z]|-?\d*\.?\d+(?:e-?\d+)?", d)
    i = 0; cmd = None; x = y = 0; sx = sy = 0; lastc = None; out = []; cur = []
    def num():
        nonlocal i; v = float(toks[i]); i += 1; return v
    def cubic(p0, p1, p2, p3):
        for k in range(1, steps + 1):
            t = k / steps; u = 1 - t
            cur.append((u**3*p0[0]+3*u*u*t*p1[0]+3*u*t*t*p2[0]+t**3*p3[0], u**3*p0[1]+3*u*u*t*p1[1]+3*u*t*t*p2[1]+t**3*p3[1]))
    while i < len(toks):
        if re.match(r"[A-Za-z]", toks[i]): cmd = toks[i]; i += 1
        if cmd in "Mm":
            nx, ny = num(), num()
            if cmd == "m": nx += x; ny += y
            if cur: out.append(cur)
            x, y = nx, ny; sx, sy = x, y; cur = [(x, y)]; cmd = "l" if cmd == "m" else "L"; lastc = None
        elif cmd in "Cc":
            a = [num() for _ in range(6)]
            if cmd == "c": a = [a[0]+x, a[1]+y, a[2]+x, a[3]+y, a[4]+x, a[5]+y]
            cubic((x, y), (a[0], a[1]), (a[2], a[3]), (a[4], a[5])); lastc = (a[2], a[3]); x, y = a[4], a[5]
        elif cmd in "Ss":
            a = [num() for _ in range(4)]
            if cmd == "s": a = [a[0]+x, a[1]+y, a[2]+x, a[3]+y]
            c1 = (2*x - lastc[0], 2*y - lastc[1]) if lastc else (x, y)
            cubic((x, y), c1, (a[0], a[1]), (a[2], a[3])); lastc = (a[0], a[1]); x, y = a[2], a[3]
        elif cmd in "Ll":
            nx, ny = num(), num()
            if cmd == "l": nx += x; ny += y
            x, y = nx, ny; cur.append((x, y)); lastc = None
        elif cmd in "Zz":
            x, y = sx, sy
            if cur: out.append(cur); cur = []
            lastc = None
    if cur: out.append(cur)
    return out

def mark_mask(size, scale, ox, oy):
    m = Image.new("L", size, 0); d = ImageDraw.Draw(m)
    outer = polys(OUTER)
    # outer ring: first polygon filled, second (the hole) cleared
    d.polygon([(ox + px*scale, oy + py*scale) for px, py in outer[0]], fill=255)
    d.polygon([(ox + px*scale, oy + py*scale) for px, py in outer[1]], fill=0)
    d.polygon([(ox + px*scale, oy + py*scale) for px, py in polys(INNER)[0]], fill=255)
    return m

def gradient(size, c1, c2, angle_pts):
    (x1, y1), (x2, y2) = angle_pts
    w, h = size; img = Image.new("RGB", size); px = img.load()
    dx, dy = x2 - x1, y2 - y1; L = dx*dx + dy*dy
    for yy in range(h):
        for xx in range(w):
            t = max(0, min(1, ((xx-x1)*dx + (yy-y1)*dy) / L))
            px[xx, yy] = tuple(int(c1[k] + (c2[k]-c1[k])*t) for k in range(3))
    return img

def orb(d):
    """A glossy sphere in Jellyfin's purple-to-blue with the white mark, diameter d, on transparent."""
    S = d * 4  # supersample
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ball = gradient((S, S), (0xAA, 0x5C, 0xC3), (0x00, 0xA4, 0xDC), ((S*0.15, S*0.1), (S*0.85, S*0.95)))
    mask = Image.new("L", (S, S), 0); ImageDraw.Draw(mask).ellipse((0, 0, S-1, S-1), fill=255)
    img.paste(ball, (0, 0), mask)
    # darken the lower edge for depth
    shade = Image.new("RGBA", (S, S), (0, 0, 0, 0)); sd = ImageDraw.Draw(shade)
    for k in range(40):
        a = int(90 * (k / 40) ** 2)
        sd.ellipse((k*S/160, k*S/160, S-1-k*S/160, S-1-k*S/160), outline=(10, 20, 60, 0))
    edge = Image.new("L", (S, S), 0); ed = ImageDraw.Draw(edge)
    for r in range(0, S//2, 4):
        v = int(110 * max(0, (r - S*0.30)) / (S*0.2))
        ed.ellipse((S/2-r, S/2-r+S*0.05, S/2+r, S/2+r+S*0.05), outline=min(110, v), width=5)
    dark = Image.new("RGBA", (S, S), (8, 16, 48, 255))
    img = Image.composite(dark, img, Image.composite(edge, Image.new("L", (S, S), 0), mask)) if False else img
    # white mark
    sc = S * 0.62 / 512; ox = S/2 - 256*sc; oy = S/2 - 262*sc
    m = mark_mask((S, S), sc, ox, oy)
    img.paste((255, 255, 255, 245), (0, 0), m)
    # gloss: a soft ellipse over the top half, fading downward
    gloss = Image.new("L", (S, S), 0); ImageDraw.Draw(gloss).ellipse((S*0.16, S*0.03, S*0.84, S*0.55), fill=200)
    gloss = gloss.filter(ImageFilter.GaussianBlur(S/45))
    fade = Image.linear_gradient("L").resize((S, S)).point(lambda v: 255 - v)
    fade = fade.transform((S, S), Image.AFFINE, (1, 0, 0, 0, 1.8, 0))
    gloss = Image.composite(gloss, Image.new("L", (S, S), 0), fade)
    gloss = Image.composite(gloss, Image.new("L", (S, S), 0), mask)
    img = Image.alpha_composite(img, Image.merge("RGBA", (Image.new("L", (S, S), 255),)*3 + (gloss.point(lambda v: v*0.55),)))
    ring = Image.new("RGBA", (S, S), (0, 0, 0, 0)); ImageDraw.Draw(ring).ellipse((2, 2, S-3, S-3), outline=(255, 255, 255, 110), width=max(2, S//120))
    img = Image.alpha_composite(img, ring)
    return img.resize((d, d), Image.LANCZOS)

if __name__ == "__main__":
    import sys
    out = sys.argv[1]
    W, H = 640, 360
    bg = gradient((W, H), (0x03, 0x0B, 0x22), (0x0C, 0x3C, 0x86), ((0, 0), (W, H)))
    glow = Image.new("L", (W, H), 0); ImageDraw.Draw(glow).ellipse((W*0.45, H*0.55, W*1.2, H*1.5), fill=150)
    glow = glow.filter(ImageFilter.GaussianBlur(70))
    bg = Image.composite(Image.new("RGB", (W, H), (0x3A, 0x9A, 0xF0)), bg, glow.point(lambda v: v*0.45))
    bg = bg.convert("RGBA")
    o = orb(150)
    halo = Image.new("L", (W, H), 0); ImageDraw.Draw(halo).ellipse((40, 90, 250, 290), fill=120); halo = halo.filter(ImageFilter.GaussianBlur(30))
    bg = Image.composite(Image.new("RGBA", (W, H), (0x70, 0x90, 0xF0, 255)), bg, halo.point(lambda v: v*0.5))
    bg.alpha_composite(o, (70, 105))
    d = ImageDraw.Draw(bg)
    f1 = ImageFont.truetype("/usr/share/fonts/truetype/lato/Lato-Light.ttf", 60)
    f2 = ImageFont.truetype("/usr/share/fonts/truetype/lato/Lato-Regular.ttf", 28)
    d.text((248, 118), "Media", font=f1, fill=(255, 255, 255))
    d.text((248, 178), "Center", font=f1, fill=(255, 255, 255))
    d.text((252, 252), "for Jellyfin", font=f2, fill=(0x9E, 0xD4, 0xF7))
    bg.convert("RGB").save(out)
    orb(512).save(out.replace(".png", "_orb.png"))
