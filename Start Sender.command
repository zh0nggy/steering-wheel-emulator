#!/bin/bash
# Double-click on the Mac to open the Tilt Wheel sender.
# macimu reads the motion sensor as root, so this asks for your password once.
# First time only: chmod +x "Start Sender.command"
cd "$(dirname "$0")" || exit 1
# Resolve Python before sudo so it cannot switch to a different installation.
sender_python="$(python3 -c 'import sys; print(sys.executable)')" || exit 1
echo "Tilt Wheel needs your Mac password to read the motion sensor."
# Check the elevated interpreter too: root can have a different package path.
if ! sudo "$sender_python" -c 'from PIL import Image, ImageDraw, ImageFont, ImageTk'; then
    echo "Smooth controller artwork requires Pillow in this Python installation."
    printf 'Install it with: sudo "%s" -m pip install Pillow\n' "$sender_python"
    read -r -p "Press Return to close. "
    exit 1
fi
sudo "$sender_python" sender_app.py
