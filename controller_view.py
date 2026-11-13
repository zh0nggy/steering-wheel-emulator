"""Clickable Xbox-controller picture for binding Mac keys to pad buttons.

Click a button on the drawing, then press the key you want for it. Escape
cancels, Backspace/Delete clears the binding. Bound controls show their
assigned keys; instructions stay below the drawing. Held controls light up.

Bindings are {target: tk keysym}. Targets are the protocol.BUTTONS names plus
"RT" and "LT" for the analog triggers (held key = pedal ramps up).
"""

import tkinter as tk
from tkinter import font as tkfont

from app_common import is_dark
from controller_art import ControllerArtwork, FACE_COLORS, configure_controller_canvas

# Short display names for keys assigned to controller buttons.
KEY_LABELS = {
    "Shift_L": "LShf", "Shift_R": "RShf", "Control_L": "LCtl",
    "Control_R": "RCtl", "Alt_L": "LOpt", "Alt_R": "ROpt",
    "Meta_L": "LCmd", "Meta_R": "RCmd", "Super_L": "LCmd", "Super_R": "RCmd",
    "Return": "Ret", "Tab": "Tab", "space": "Spc", "BackSpace": "Bksp",
    "Up": "↑", "Down": "↓", "Left": "←", "Right": "→",
    "comma": ",", "period": ".", "slash": "/", "semicolon": ";",
    "apostrophe": "'", "bracketleft": "[", "bracketright": "]",
    "minus": "-", "equal": "=", "grave": "`", "backslash": "\\",
}

WIDE_LABELS = {
    "Shift_L": "Left Shift", "Shift_R": "Right Shift", "Control_L": "Left Ctrl",
    "Control_R": "Right Ctrl", "Alt_L": "Left Option", "Alt_R": "Right Option",
    "Meta_L": "Left Cmd", "Meta_R": "Right Cmd", "Super_L": "Left Cmd",
    "Super_R": "Right Cmd", "Return": "Return", "space": "Space",
    "Up": "Up arrow", "Down": "Down arrow", "Left": "Left arrow", "Right": "Right arrow",
}

# Keys that can't be bound: they control the binding itself, or macOS latches
# them instead of reporting hold (caps lock), which would make a stuck button.
RESERVED = {"Escape", "BackSpace", "Delete", "Caps_Lock"}

TARGET_NAMES = {
    "A": "A", "B": "B", "X": "X", "Y": "Y",
    "LB": "Left bumper", "RB": "Right bumper",
    "LT": "Left trigger", "RT": "Right trigger",
    "VIEW": "View", "MENU": "Menu", "GUIDE": "Xbox button",
    "LS": "Left stick click", "RS": "Right stick click",
    "DPAD_UP": "D-pad up", "DPAD_DOWN": "D-pad down",
    "DPAD_LEFT": "D-pad left", "DPAD_RIGHT": "D-pad right",
}

# Defaults: pedals on the two Shift keys, gears on Tab (up) / Return (down).
DEFAULT_BINDINGS = {"LT": "Shift_R", "RT": "Shift_L", "X": "Return", "A": "Tab"}


def key_label(keysym):
    if keysym is None:
        return ""
    if keysym in KEY_LABELS:
        return KEY_LABELS[keysym]
    if len(keysym) == 1:
        return keysym.upper()
    return keysym[:4]  # e.g. F5, KP_1 -> fits the button


def normalise(keysym):
    """Tk keysym -> the name we store. Letters are case-folded so Shift+A
    doesn't count as a different key from A."""
    return keysym.lower() if len(keysym) == 1 else keysym


# Two palettes; ControllerView picks one from the window's real background so
# the drawing blends in with light and dark mode (macOS dark mode included).
LIGHT = {
    "outline": "#555", "body": "#e9e9e9", "ring": "#d6d6d6", "button": "#ffffff",
    "bound": "#dbe9fb", "held": "#2b7de9", "picking": "#ffd75e",
    "key_text": "#222", "label": "#777", "hint": "#555", "hint_active": "#b06000",
}
DARK = {
    "outline": "#9a9a9a", "body": "#46464a", "ring": "#2c2c2e", "button": "#636368",
    "bound": "#2f4f78", "held": "#2b7de9", "picking": "#c79a1e",
    "key_text": "#f2f2f2", "label": "#9a9a9a", "hint": "#a8a8a8", "hint_active": "#f0a63a",
}
class ControllerView(ControllerArtwork, tk.Frame):
    """The shared drawing with contextual binding instructions.

    on_change(bindings) is called whenever a binding is set or cleared.
    """

    ROW_GAP = 13
    BINDING_HINTS = True
    RAISED_TRIGGERS = True
    # Starting height; __init__ trims the canvas to fit the finished drawing.
    W, H = 440, 400

    def __init__(self, parent, bindings, on_change=None, *, hint_parent=None):
        super().__init__(parent)
        self.bindings = dict(bindings)
        self.on_change = on_change
        self.picking = None
        self.held_targets = set()
        self.listeners = []
        self.shapes = {}   # target -> canvas item to recolour
        self.labels = {}   # target -> text or icon, kept identical to Windows
        self.key_labels = {}  # replacement text for bound D-pad/Xbox icons

        # Same background as the window, so the drawing sits on it rather than
        # in a pale box; then colours to suit a light or dark window.
        self.colors = DARK if is_dark(self) else LIGHT
        self.canvas = tk.Canvas(self, width=self.W, height=self.H, bg=self.cget("bg"),
                                highlightthickness=0)
        self._native_art = configure_controller_canvas(self.canvas)
        self.canvas.pack()
        self.hint = tk.Label(hint_parent if hint_parent is not None else self,
                             anchor="w", justify="left", fg=self.colors["hint"],
                             wraplength=self.W, height=2 if self.BINDING_HINTS else 0)
        self.hint.pack(fill="x", pady=(2, 0))
        # Reset goes in a strip above the drawing, top-right corner.
        self.reset_btn = tk.Button(self.canvas, text="Reset to defaults",
                                   command=self.reset)
        self.canvas.create_window(self.W - 4, 3, window=self.reset_btn, anchor="ne")
        self._draw()
        self.body_shift = self._space_shoulders()
        top = self._drawing_top()
        drawn = [i for i in self.canvas.find_all() if self.canvas.type(i) != "window"]
        for item in drawn:
            self.canvas.move(item, 0, top)
        # Shared coordinates after fitting the drawing beneath the top strip.
        self.offset = top + self.body_shift
        # Preserve the same silhouette and proportions as the Windows drawing.
        lowest = max(bounds[3] for item in drawn if (bounds := self.canvas.bbox(item)) is not None)
        self._natural_height = lowest + 2
        self.canvas.config(height=self._natural_height)
        self._refresh_all()
        self._set_hint()

    # ---------- drawing ----------

    def _draw_front_controls(self):
        """The shared Xbox layout, independent of the shoulder artwork."""
        c = self.canvas
        mid = self.W / 2
        # Sticks: outer ring is the (unbindable) stick, inner disc is the click.
        for target, (x, y) in zip(("LS", "RS"), self.STICK_CENTERS):
            c.create_oval(x - 30, y - 30, x + 30, y + 30, fill=self.colors["ring"],
                          outline=self.colors["outline"], width=2)
            self._button(target, c.create_oval(x - 19, y - 19, x + 19, y + 19), (x, y))

        self._draw_dpad()

        # Face buttons in the usual diamond.
        fx, fy = self.FACE_CENTER
        d, r = self.FACE_SPACING, 16
        for target, (x, y) in (("Y", (fx, fy - d)), ("B", (fx + d, fy)),
                               ("A", (fx, fy + d)), ("X", (fx - d, fy))):
            self._button(target, c.create_oval(x - r, y - r, x + r, y + r), (x, y))

        for target, dx, y, radius in self.SYSTEM_BUTTONS:
            x = mid + dx
            shape = c.create_oval(x - radius, y - radius, x + radius, y + radius)
            self._button(target, shape, (x, y), icon_points=self.SYSTEM_ICONS.get(target))


    def fit_height(self, height):
        """Keep the shared controller proportions; return extra space as padding."""
        return max(0, height - self._natural_height)

    def _button(self, target, item, label_at, *, icon_points=None):
        c = self.canvas
        c.itemconfig(item, outline=self.colors["outline"], width=2, fill=self.colors["button"])
        if icon_points is None:
            label = c.create_text(*label_at, text="", font=("Helvetica", 8))
        else:
            x, y = label_at
            label = c.create_polygon([coord for dx, dy in icon_points for coord in (x + dx, y + dy)])
        self.shapes[target] = item
        self.labels[target] = label
        items = [item, label]
        if icon_points is not None and self.BINDING_HINTS:
            key_text = c.create_text(*label_at, text="", state="hidden")
            self.key_labels[target] = key_text
            items.append(key_text)
        for tag in items:
            c.tag_bind(tag, "<Button-1>", lambda _e, t=target: self.start_pick(t))
            c.tag_bind(tag, "<Enter>", lambda _e, t=target: self._hover(t))
            c.tag_bind(tag, "<Leave>", lambda _e: self._leave())

    # ---------- state ----------

    def _refresh(self, target):
        self.paint_target(target, target in self.bindings,
                          held=target in self.held_targets, picking=target == self.picking)
        key = self.bindings.get(target)
        if target in self.key_labels:
            self.canvas.itemconfig(self.labels[target], state="hidden" if key else "normal")
            label = self.key_labels[target]
            self.canvas.itemconfig(label, state="normal" if key else "hidden")
        else:
            label = self.labels[target]
        if key:
            text = key_label(key)
            coords = self.canvas.coords(self.shapes[target])
            width = max(coords[::2]) - min(coords[::2]) - 4
            height = max(coords[1::2]) - min(coords[1::2]) - 4
            size = 11 if len(text) == 1 else 8
            while size > 5:
                font = tkfont.Font(root=self.canvas, family="Helvetica", size=size, weight="bold")
                if font.measure(text) <= width and font.metrics("linespace") <= height:
                    break
                size -= 1
            self.canvas.itemconfig(label, text=text, font=("Helvetica", size, "bold"),
                                   fill=self.canvas.itemcget(self.labels[target], "fill"))

    def _refresh_all(self):
        for target in self.shapes:
            self._refresh(target)
        self._redraw_art()

    def _set_hint(self, extra=""):
        if self.picking:
            text = (f"Press a key for {TARGET_NAMES[self.picking]}.  "
                    "Esc = cancel, Backspace = clear.")
        elif self.BINDING_HINTS:
            text = ("Click a controller button, then press a key to bind it.\n"
                    "Esc cancels. Backspace clears. Blue outline = assigned.")
        else:
            text = "Click a control to bind a Mac key. Blue outline = bound."
        self.hint.config(text=text + (f"  {extra}" if extra else ""),
                         fg=self.colors["hint_active" if self.picking else "hint"])

    def _hover(self, target):
        self.canvas.config(cursor="hand2")

    def _leave(self):
        self.canvas.config(cursor="")

    def start_pick(self, target):
        previous, self.picking = self.picking, target
        if previous:
            self._refresh(previous)
        self._refresh(target)
        self._redraw_art()
        self._set_hint()
        for callback in self.listeners:
            callback()

    def reset(self):
        self.set_bindings(DEFAULT_BINDINGS)

    def set_bindings(self, bindings):
        """Replace every binding at once (presets, Clear all, Reset)."""
        self.picking = None
        self.bindings = dict(bindings)
        self._changed()

    def add_listener(self, callback):
        """callback() runs after any binding or picking change, so other
        views (the game setup tab) can redraw."""
        self.listeners.append(callback)

    def _changed(self, note=""):
        self._refresh_all()
        self._set_hint(note)
        if self.on_change:
            self.on_change(dict(self.bindings))
        for callback in self.listeners:
            callback()

    def handle_key(self, keysym):
        """Called for every key press. Returns True if it was used for binding
        (so it must not also be treated as a game input)."""
        if self.picking is None:
            return False
        target, self.picking = self.picking, None
        note = ""
        if keysym == "Escape":
            pass
        elif keysym in ("BackSpace", "Delete"):
            self.bindings.pop(target, None)
        elif keysym in RESERVED:
            note = f"{key_label(keysym)} can't be used."
        else:
            key = normalise(keysym)
            # One key drives one button: move it off anything else first.
            for other, bound in list(self.bindings.items()):
                if bound == key and other != target:
                    del self.bindings[other]
                    note = f"(moved from {TARGET_NAMES[other]})"
            self.bindings[target] = key
        self._changed(note)
        return True

    def show_held(self, targets):
        """Light up the buttons whose keys are held right now."""
        targets = set(targets)
        if targets == self.held_targets:
            return
        changed = targets ^ self.held_targets
        self.held_targets = targets
        for target in changed:
            if target in self.shapes:
                self._refresh(target)
        self._redraw_art()
