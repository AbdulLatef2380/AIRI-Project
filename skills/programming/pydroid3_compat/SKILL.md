---
name: pydroid3_compat
description: Keep a Python program runnable in Pydroid 3 on Android: single file, GUI toolkit choice, packages, Arabic text and how to move the file there.
---

# Pydroid 3 compatibility

Use this together with python_app when the user runs Python on an Android phone with Pydroid 3 (or says "Pydroid"). These points are general guidance; tell the user to confirm on the device and never state that the program was tested.

- Deliver one self-contained .py file. Do not depend on extra files such as fonts, images or config files that the user would also have to copy.
- Toolkits: tkinter and kivy are the usual choices in Pydroid 3. Pick tkinter unless the user asks for kivy. If kivy is requested, write a normal kivy App class and name it in the reply so the user can install kivy from Pydroid's pip screen if it is missing.
- Packages: stay with the standard library. For anything else, list the pip package names in the reply. Avoid packages that need a C compiler or system libraries.
- Screen: phones are narrow. Use grid or pack layouts that stretch, large buttons, and a font size of at least 18.
- Arabic text: tkinter may show disconnected or reversed letters. Use arabic_reshaper and python-bidi inside try/except when available, otherwise show the plain text.
- Do not use input() or the terminal for a GUI app, and do not read or write outside the program's own folder.
- Getting the file there: the app shows a file card with Save and Share. The user saves or shares the file, then opens it in Pydroid 3 and runs it.
