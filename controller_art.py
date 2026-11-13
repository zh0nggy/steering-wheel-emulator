"""Shared Mac/Windows controller artwork, with optional smooth rendering."""

from functools import lru_cache

from app_common import is_dark

FACE_COLORS = {"A": "#2fa84f", "B": "#d64545", "X": "#2b7de9", "Y": "#d9a400"}
ADDED_COLOR = "#2f4f78"

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
    candidates = (("seguisym.ttf", "/System/Library/Fonts/Apple Symbols.ttf", "DejaVuSans.ttf")
                  if symbol else
                  ("segoeuib.ttf", "/System/Library/Fonts/Helvetica.ttc", "DejaVuSans-Bold.ttf"))
    for name in candidates:
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            continue
    return ImageFont.load_default(size=size)


def render_controller_image(canvas):
    """Render canvas shapes to an image; leave their native hit areas intact."""
    if Image is None:
        return None

    width, height = int(canvas["width"]), int(canvas["height"])
    background = tuple(channel >> 8 for channel in canvas.winfo_rgb(canvas["background"]))
    art = Image.new("RGB", (width * _SCALE, height * _SCALE), background)
    draw = ImageDraw.Draw(art)
    for item in canvas.find_all():
        if canvas.itemcget(item, "state") == "hidden":
            continue
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
            bounds = font.getmask(text).getbbox()
            if bounds is None:
                continue
            left, top, right, bottom = bounds
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
    return art.resize((width, height), Image.Resampling.LANCZOS)


def render_controller(canvas):
    art = render_controller_image(canvas)
    return ImageTk.PhotoImage(art, master=canvas) if art is not None else None


class ControllerArtwork:
    """The same controller geometry, palette and control labels on Mac and Windows."""

    BODY_TOP = 80

    # Cubic Bézier points in reference image coordinates. Each group of three
    # after the starting point describes one segment of the shell.
    _LEFT_SHELL = (
        (340, 120), (224, 120), (154, 124), (112, 161),
        (79, 191), (33, 329), (13, 409),
        (1, 458), (1, 504), (22, 540),
        (39, 569), (70, 586), (88, 573),
        (119, 550), (174, 482), (202, 452),
        (219, 433), (229, 432), (340, 432),
    )
    _LEFT_BUMPER = (
        (112, 161), (123, 141), (126, 132), (149, 120),
        (174, 107), (207, 102), (222, 101),
        (230, 100), (238, 114), (247, 123),
        (201, 133), (150, 144), (112, 161),
    )
    TRIGGER_TOP = 37
    STICK_CENTERS = ((108, 145), (273, 220))
    FACE_CENTER = (328, 164)
    FACE_SPACING = 32
    SYSTEM_BUTTONS = (("GUIDE", 0, 112, 17), ("VIEW", -33, 155, 11), ("MENU", 33, 155, 11))
    SYSTEM_ICONS = {
        "GUIDE": ((-12, -8), (-9, -10), (-5, -9), (0, -6), (5, -9), (9, -10),
                  (12, -8), (8, -6), (4, -3), (8, 2), (11, 7), (12, 10), (9, 11),
                  (6, 7), (3, 3), (0, 0), (-3, 3), (-6, 7), (-9, 11), (-12, 10),
                  (-11, 7), (-8, 2), (-4, -3), (-8, -6)),
    }
    CONTROL_LABELS = {"VIEW": "▣", "MENU": "≡"}

    def _space_shoulders(self):
        # The bumpers sit on the shell, so keep the drawing together.
        return 0

    def _drawing_top(self):
        if self.RAISED_TRIGGERS:
            # Keep Reset above the shoulders so it cannot cover the right trigger.
            return 3 + self.reset_btn.winfo_reqheight() + 8 - (self.TRIGGER_TOP - 2)
        # The Windows editor keeps its rectangular triggers beside Clear phone.
        return 4 - self.TRIGGER_TOP

    def _trigger_points(self, x):
        # Android's closed cubic path, offset into the desktop drawing coordinates.
        y = self.TRIGGER_TOP - 4
        return ((x - 13, y + 32), (x - 12, y + 17), (x - 10, y + 2), (x, y + 2),
                (x + 10, y + 2), (x + 12, y + 17), (x + 13, y + 32),
                (x + 13, y + 32), (x - 13, y + 32), (x - 13, y + 32))

    def _reference_point(self, x, y):
        return self.W / 2 + (x - 340) * 0.6, self.BODY_TOP + (y - 120) * 0.6

    def _body_points(self, stretch):
        left = self._LEFT_SHELL
        right = tuple((680 - x, y) for x, y in reversed(left[:-1]))
        points = []
        for x, y in left + right:
            px, py = self._reference_point(x, y)
            grip = max(0, y - 410) / (586 - 410)
            points.extend((px, py + self.offset + stretch * grip))
        return points

    def _draw(self):
        dark = is_dark(self)
        self.colors = {
            **self.colors,
            "outline": "#b8b5c3" if dark else "#27252e",
            "body": "#39383f" if dark else "#ffffff",
            "ring": "#24232b" if dark else "#625d74",
            "button": "#625e75" if dark else "#d1cedd",
            "key_text": "#f5f4f8" if dark else "#282531",
            "xbox_logo": "#67d7ff" if dark else "#007eac",
        }
        c = self.canvas
        self.offset = 0

        if self.RAISED_TRIGGERS:
            for target, reference_x in (("LT", 188), ("RT", 492)):
                x = self._reference_point(reference_x, 120)[0]
                points = [v for point in self._trigger_points(x) for v in point]
                self._button(target, c.create_polygon(points, smooth="raw", splinesteps=24),
                             (x, self.TRIGGER_TOP + 20))
        else:
            for target, x in (("LT", 20), ("RT", 78)):
                y = self.TRIGGER_TOP
                self._button(target, c.create_rectangle(x, y, x + 44, y + 24), (x + 22, y + 12))
        for target, mirror in (("LB", False), ("RB", True)):
            points = [v for x, y in self._LEFT_BUMPER
                      for v in self._reference_point(680 - x if mirror else x, y)]
            label = self._reference_point(492 if mirror else 188, 117)
            self._button(target, c.create_polygon(points, smooth="raw", splinesteps=24), label)

        # Draw the shell in front of the shoulders so they share its edge.
        self.body = c.create_polygon(
            self._body_points(0), smooth="raw", splinesteps=24,
            fill=self.colors["body"], outline=self.colors["outline"], width=2)
        for target in ("LB", "RB"):
            c.tag_raise(self.labels[target])

        self._draw_front_controls()

    def _draw_dpad(self):
        c = self.canvas
        x, y = 161, 216
        radius, reach, arm = 36, 29, 11
        c.create_oval(x - radius, y - radius, x + radius, y + radius,
                      fill=self.colors["ring"], outline=self.colors["outline"], width=2)
        cross = [(-arm, -reach), (arm, -reach), (arm, -arm), (reach, -arm),
                 (reach, arm), (arm, arm), (arm, reach), (-arm, reach),
                 (-arm, arm), (-reach, arm), (-reach, -arm), (-arm, -arm)]
        c.create_polygon([coord for dx, dy in cross for coord in (x + dx, y + dy)],
                         fill=self.colors["button"], outline=self.colors["outline"],
                         width=2, joinstyle="round")

        # Each arm is still clickable. Unmapped arms blend into the cross.
        for target, (left, top, right, bottom), (dx, dy) in (
            ("DPAD_UP", (-arm, -reach, arm, -arm), (0, -1)),
            ("DPAD_DOWN", (-arm, arm, arm, reach), (0, 1)),
            ("DPAD_LEFT", (-reach, -arm, -arm, arm), (-1, 0)),
            ("DPAD_RIGHT", (arm, -arm, reach, arm), (1, 0)),
        ):
            shape = c.create_rectangle(x + left, y + top, x + right, y + bottom)
            center = (x + (left + right) / 2, y + (top + bottom) / 2)
            # Rotate the same triangle using distances forward and sideways.
            arrow = [(forward * dx - side * dy, forward * dy + side * dx)
                     for forward, side in ((4, 0), (-4, 4), (-4, -4))]
            self._button(target, shape, center, icon_points=arrow)

    def _redraw_art(self):
        art = render_controller(self.canvas)
        if art is not None:
            self.canvas.delete("controller_art")
            self._art = art
            self.canvas.create_image(0, 0, anchor="nw", image=art,
                                     state="disabled", tags="controller_art")

    def paint_target(self, target, used, *, held=False, picking=False):
        if used:
            fill, color = FACE_COLORS.get(target, ADDED_COLOR), "white"
        else:
            fill = self.colors["button"]
            color = FACE_COLORS.get(target, self.colors["key_text"])
        is_dpad = target.startswith("DPAD_")
        outline = "" if is_dpad else self.colors["outline"]
        if target == "GUIDE" and not used:
            fill = self.colors["body"]
            outline = color = self.colors["xbox_logo"]
        outline = "#2b7de9" if used else outline
        width = 3 if used else 2
        if picking:
            fill, color, outline, width = self.colors["picking"], "#222", "#b06000", 3
        elif held:
            fill, color, outline, width = self.colors["held"], "white", "#8fb4e8", 4
        self.canvas.itemconfig(self.shapes[target], fill=fill,
                               outline=outline, width=width)
        if is_dpad or target in self.SYSTEM_ICONS:
            self.canvas.itemconfig(self.labels[target], fill=color)
            return
        size = 11 if target in FACE_COLORS else 8
        self.canvas.itemconfig(self.labels[target],
                               text=self.CONTROL_LABELS.get(target, target), fill=color,
                               font=("Helvetica", size, "bold"))
