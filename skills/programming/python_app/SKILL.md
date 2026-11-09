---
name: python_app
description: Plan, write, check and hand over a complete single-file Python program (calculator, tool, small GUI app) as a saved file.
---

# Python app workflow

Use this for any request to build a Python program. Follow the four steps in order.

## 1. Plan
List the user's requirements as short items before writing code: every named feature (for example themes, a scientific mode, a history list, colors, an Arabic font) and the language. Build every item; do not shrink "multiple themes" to a single dark mode. If the request gives no number, use at least 3 themes.

## 2. Write
- Call write_file once with a plain file name ending in .py (for example calculator.py) and the COMPLETE source. Never send empty or partial content, and never paste the program in chat instead.
- One self-contained file. Prefer the standard library. Use tkinter for a GUI unless the user names another toolkit.
- Keep themes in one table named THEMES (a dict of dicts: name -> background, foreground, button and accent colors) so each theme is a real entry, and add a way to switch themes at run time.
- A scientific mode uses the math module (sin, cos, tan, sqrt, log, factorial, pow and constants). Never run eval() on raw user input; parse the expression with the ast module and allow only numbers, operators and the listed functions.
- Keep a history list when a history is requested, and show it in the UI.
- Arabic UI text: write the labels in Arabic and choose a font that has Arabic glyphs. Text shaping may be wrong without the arabic_reshaper and python-bidi packages, so import them inside try/except and fall back to the plain text when they are missing.
- Guard the entry point with `if __name__ == "__main__":`.

## 3. Check
The write_file result carries an AIRI syntax check and a requirements line. If it reports a failure or a missing requirement, fix it and call write_file again with the same file name, overwrite set to true, and the complete updated file. The check is structural only: the program was NOT run.

## 4. Hand over
Reply briefly: the file name, how to run it, and the main features. The app shows a file card with Save and Share buttons, so tell the user to use it. Never claim the program was run, tested or saved to a folder you did not see.
