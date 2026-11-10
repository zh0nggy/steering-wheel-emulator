"""Render the receiver's clickable canvas artwork with smooth edges."""

from functools import lru_cache

try:
    from PIL import Image, ImageDraw, ImageFont, ImageTk
except ImportError:
    Image = None  # A source checkout can still use Tk's own drawing.


_SCALE = 4


def _cubic_polyline(points):
    """Sample a Tk raw spline: one starting point, then three per segment."""
    result = [points[0]]
    for start in range(0, len(points) - 3, 3):
        segment = points[start:start + 4]
        for step in range(1, 25):
            t = step / 24
            u = 1 - t
            weights = (u ** 3, 3 * u * u * t, 3 * u * t * t, t ** 3)
            result.append(tuple(sum(value * weight for value, weight in zip(axis, weights))
                                for axis in zip(*segment)))
    return result


@lru_cache(maxsize=16)
def _label_font(size, symbol):
    try:
        return ImageFont.truetype("seguisym.ttf" if symbol else "segoeuib.ttf", size)
    except OSError:
        return ImageFont.load_default(size=size)


def render_controller(canvas):
    """Return a display image. The original canvas items provide hit testing."""
    if Image is None:
        return None

    width, height = int(canvas["width"]), int(canvas["height"])
    background = tuple(channel >> 8 for channel in canvas.winfo_rgb(canvas["background"]))
    art = Image.new("RGB", (width * _SCALE, height * _SCALE), background)
    draw = ImageDraw.Draw(art)
    for item in canvas.find_all():
        kind = canvas.type(item)
        if kind not in ("polygon", "rectangle", "oval", "text"):
            continue
        coords = [coord * _SCALE for coord in canvas.coords(item)]
        fill = canvas.itemcget(item, "fill") or None
        if kind == "text":
            text = canvas.itemcget(item, "text")
            if not text:
                continue
            _, size, *_ = canvas.tk.splitlist(canvas.itemcget(item, "font"))
            pixels = round(canvas.winfo_fpixels(f"{size}p") * _SCALE)
            font = _label_font(pixels, not text.isascii())
            left, top, right, bottom = font.getmask(text).getbbox()
            origin_x, origin_y = font.getbbox(text)[:2]
            x, y = coords
            draw.text((x - origin_x - (left + right) / 2, y - origin_y - (top + bottom) / 2),
                      text, font=font, fill=fill)
            continue

        outline = canvas.itemcget(item, "outline") or None
        stroke = round(float(canvas.itemcget(item, "width")) * _SCALE)
        if kind == "polygon":
            points = list(zip(coords[::2], coords[1::2]))
            if canvas.itemcget(item, "smooth") == "raw":
                points = _cubic_polyline(points)
            draw.polygon(points, fill=fill)
            if outline and stroke:
                draw.line(points + [points[0]], fill=outline, width=stroke, joint="curve")
        else:
            # Pillow draws the stroke inside the box. Expand by half a stroke
            # to match Tk's centered border and keep the hit areas aligned.
            half = stroke / 2 if outline else 0
            x0, y0, x1, y1 = coords
            shape = draw.ellipse if kind == "oval" else draw.rectangle
            shape((x0 - half, y0 - half, x1 + half, y1 + half),
                  fill=fill, outline=outline, width=stroke)
    return ImageTk.PhotoImage(art.resize((width, height), Image.Resampling.LANCZOS), master=canvas)
