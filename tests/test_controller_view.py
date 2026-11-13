import tkinter as tk
import unittest
from unittest.mock import Mock, patch

import controller_art
from controller_view import ControllerView, DEFAULT_BINDINGS, TARGET_NAMES
from phone_layout import PhoneControllerView


class ControllerViewTests(unittest.TestCase):
    def setUp(self):
        try:
            self.root = tk.Tk()
        except tk.TclError as exc:
            self.skipTest(f"Tk display unavailable: {exc}")
        self.root.withdraw()
        self.addCleanup(self.root.destroy)

    def pixels(self, view):
        return controller_art.render_controller_image(view.canvas).tobytes()

    @unittest.skipIf(controller_art.Image is None, "Pillow is optional")
    def test_mac_and_windows_keep_identical_controller_body_for_matching_selections(self):
        for targets in ((), tuple(DEFAULT_BINDINGS), tuple(TARGET_NAMES)):
            with self.subTest(targets=targets):
                bindings = {target: f"F{index + 1}" for index, target in enumerate(targets)}
                mac = ControllerView(self.root, bindings)
                windows = PhoneControllerView(self.root, {})
                for target in targets:
                    windows.paint_target(target, True)
                # Raised triggers add a header row. Compare the rendered body
                # geometry relative to that offset, without raster rounding noise.
                pairs = [(mac.body, windows.body)] + [
                    (mac.shapes[target], windows.shapes[target])
                    for target in TARGET_NAMES if target not in ("LT", "RT")
                ]
                for mac_item, windows_item in pairs:
                    mac_coords = mac.canvas.coords(mac_item)
                    windows_coords = windows.canvas.coords(windows_item)
                    self.assertEqual(len(mac_coords), len(windows_coords))
                    for index, (a, b) in enumerate(zip(mac_coords, windows_coords)):
                        self.assertAlmostEqual(a - (mac.offset if index % 2 else 0),
                                               b - (windows.offset if index % 2 else 0))
                    self.assertEqual(mac.canvas.itemcget(mac_item, "fill"),
                                     windows.canvas.itemcget(windows_item, "fill"))
                self.assertEqual("polygon", mac.canvas.type(mac.shapes["LT"]))
                self.assertEqual("polygon", mac.canvas.type(mac.shapes["RT"]))
                self.assertEqual(set(TARGET_NAMES), set(mac.shapes))
                mac.destroy()
                windows.destroy()

    def test_rebinding_moves_keys_clears_and_updates_visible_key_labels(self):
        changed = Mock()
        view = ControllerView(self.root, DEFAULT_BINDINGS, on_change=changed)
        view.start_pick("GUIDE")
        self.assertTrue(view.handle_key("Return"))
        self.assertEqual("Return", view.bindings["GUIDE"])
        self.assertEqual("Ret", view.canvas.itemcget(view.key_labels["GUIDE"], "text"))
        self.assertEqual("hidden", view.canvas.itemcget(view.labels["GUIDE"], "state"))
        self.assertNotIn("X", view.bindings)
        changed.assert_called_with(view.bindings)
        view.start_pick("DPAD_UP")
        self.assertTrue(view.handle_key("W"))
        self.assertEqual("w", view.bindings["DPAD_UP"])
        self.assertEqual("W", view.canvas.itemcget(view.key_labels["DPAD_UP"], "text"))
        view.start_pick("DPAD_UP")
        view.handle_key("Escape")
        self.assertEqual("w", view.bindings["DPAD_UP"])
        view.start_pick("DPAD_UP")
        view.handle_key("BackSpace")
        self.assertNotIn("DPAD_UP", view.bindings)
        self.assertEqual("normal", view.canvas.itemcget(view.labels["DPAD_UP"], "state"))
        self.assertEqual("hidden", view.canvas.itemcget(view.key_labels["DPAD_UP"], "state"))
        view.reset()
        self.assertEqual(DEFAULT_BINDINGS, view.bindings)
        self.assertEqual("Tab", view.canvas.itemcget(view.labels["A"], "text"))
        self.assertEqual("Ret", view.canvas.itemcget(view.labels["X"], "text"))
        self.assertEqual("RShf", view.canvas.itemcget(view.labels["LT"], "text"))
        self.assertEqual("LShf", view.canvas.itemcget(view.labels["RT"], "text"))
        instructions = view.hint.cget("text")
        self.assertIn("Click a controller button", instructions)
        self.assertIn("Backspace clears", instructions)
        view._hover("LT")
        self.assertEqual(instructions, view.hint.cget("text"))
        self.assertEqual("pack", view.hint.winfo_manager())
        self.assertFalse(view.canvas.find_withtag("binding_hint"))
        view._leave()
        self.assertEqual(instructions, view.hint.cget("text"))
        self.assertFalse(view.canvas.find_withtag("binding_hint"))

    @unittest.skipIf(controller_art.Image is None, "Pillow is optional")
    def test_picking_and_held_changes_repaint_visible_art_and_preserve_bindings(self):
        view = ControllerView(self.root, DEFAULT_BINDINGS)
        idle = self.pixels(view)
        initial_art = view._art
        view.show_held({"A", "LT"})
        self.assertIsNot(initial_art, view._art)
        self.assertNotEqual(idle, self.pixels(view))
        held_art = view._art
        view.show_held({"A", "LT"})
        self.assertIs(held_art, view._art)
        view.show_held(set())
        self.assertEqual(idle, self.pixels(view))
        view.start_pick("A")
        self.assertNotEqual(idle, self.pixels(view))
        self.assertIn("Press a key for A", view.hint.cget("text"))
        view.handle_key("Escape")
        self.assertEqual(idle, self.pixels(view))
        self.assertEqual(DEFAULT_BINDINGS, view.bindings)

    @unittest.skipIf(controller_art.Image is None, "Pillow is optional")
    def test_fitting_mac_panel_keeps_windows_controller_proportions(self):
        view = ControllerView(self.root, DEFAULT_BINDINGS)
        original = self.pixels(view)
        view.fit_height(view._natural_height + 100)
        self.assertEqual(original, self.pixels(view))

    def test_binding_still_works_without_optional_smooth_renderer(self):
        with patch.object(controller_art, "Image", None):
            view = ControllerView(self.root, DEFAULT_BINDINGS)
            view.start_pick("DPAD_RIGHT")
            view.handle_key("Right")
            self.assertEqual("Right", view.bindings["DPAD_RIGHT"])
            self.assertFalse(view.canvas.find_withtag("controller_art"))


if __name__ == "__main__":
    unittest.main()
