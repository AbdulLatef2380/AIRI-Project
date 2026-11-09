package com.airi.assistant.agent.loop.skill

/**
 * A skill's instructions as the agent sees them. The prompt carries only [name] and
 * [description] (see [AgentSkillCatalog.promptCatalog]); the full [body] is returned when the
 * model calls load_skill. The same text lives in skills/programming/<name>/SKILL.md; a unit test
 * keeps the two from drifting apart.
 */
data class AgentSkillDoc(val name: String, val description: String, val body: String)

object AgentSkillCatalog {

    val skills: List<AgentSkillDoc> = listOf(
        AgentSkillDoc(
            name = "python_app",
            description = "Plan, write, check and hand over a complete single-file Python program (calculator, tool, small GUI app) as a saved file.",
            body =
            "# Python app workflow\n" +
            "\n" +
            "Use this for any request to build a Python program. Follow the four steps in order.\n" +
            "\n" +
            "## 1. Plan\n" +
            "List the user's requirements as short items before writing code: every named feature (for example themes, a scientific mode, a history list, colors, an Arabic font) and the language. Build every item; do not shrink \"multiple themes\" to a single dark mode. If the request gives no number, use at least 3 themes.\n" +
            "\n" +
            "## 2. Write\n" +
            "- Call write_file once with a plain file name ending in .py (for example calculator.py) and the COMPLETE source. Never send empty or partial content, and never paste the program in chat instead.\n" +
            "- One self-contained file. Prefer the standard library. Use tkinter for a GUI unless the user names another toolkit.\n" +
            "- Keep themes in one table named THEMES (a dict of dicts: name -> background, foreground, button and accent colors) so each theme is a real entry, and add a way to switch themes at run time.\n" +
            "- A scientific mode uses the math module (sin, cos, tan, sqrt, log, factorial, pow and constants). Never run eval() on raw user input; parse the expression with the ast module and allow only numbers, operators and the listed functions.\n" +
            "- Keep a history list when a history is requested, and show it in the UI.\n" +
            "- Arabic UI text: write the labels in Arabic and choose a font that has Arabic glyphs. Text shaping may be wrong without the arabic_reshaper and python-bidi packages, so import them inside try/except and fall back to the plain text when they are missing.\n" +
            "- Guard the entry point with `if __name__ == \"__main__\":`.\n" +
            "\n" +
            "## 3. Check\n" +
            "The write_file result carries an AIRI syntax check and a requirements line. If it reports a failure or a missing requirement, fix it and call write_file again with the same file name, overwrite set to true, and the complete updated file. The check is structural only: the program was NOT run.\n" +
            "\n" +
            "## 4. Hand over\n" +
            "Reply briefly: the file name, how to run it, and the main features. The app shows a file card with Save and Share buttons, so tell the user to use it. Never claim the program was run, tested or saved to a folder you did not see.\n",
        ),
        AgentSkillDoc(
            name = "pydroid3_compat",
            description = "Keep a Python program runnable in Pydroid 3 on Android: single file, GUI toolkit choice, packages, Arabic text and how to move the file there.",
            body =
            "# Pydroid 3 compatibility\n" +
            "\n" +
            "Use this together with python_app when the user runs Python on an Android phone with Pydroid 3 (or says \"Pydroid\"). These points are general guidance; tell the user to confirm on the device and never state that the program was tested.\n" +
            "\n" +
            "- Deliver one self-contained .py file. Do not depend on extra files such as fonts, images or config files that the user would also have to copy.\n" +
            "- Toolkits: tkinter and kivy are the usual choices in Pydroid 3. Pick tkinter unless the user asks for kivy. If kivy is requested, write a normal kivy App class and name it in the reply so the user can install kivy from Pydroid's pip screen if it is missing.\n" +
            "- Packages: stay with the standard library. For anything else, list the pip package names in the reply. Avoid packages that need a C compiler or system libraries.\n" +
            "- Screen: phones are narrow. Use grid or pack layouts that stretch, large buttons, and a font size of at least 18.\n" +
            "- Arabic text: tkinter may show disconnected or reversed letters. Use arabic_reshaper and python-bidi inside try/except when available, otherwise show the plain text.\n" +
            "- Do not use input() or the terminal for a GUI app, and do not read or write outside the program's own folder.\n" +
            "- Getting the file there: the app shows a file card with Save and Share. The user saves or shares the file, then opens it in Pydroid 3 and runs it.\n",
        ),
    )

    fun find(name: String): AgentSkillDoc? {
        val key = name.trim().lowercase().replace('-', '_').replace(' ', '_')
        return skills.firstOrNull { it.name == key }
    }

    val names: List<String> get() = skills.map { it.name }

    /** Names and one-line descriptions only, for the load_skill tool description. */
    fun promptCatalog(): String = skills.joinToString("\n") { "- ${it.name}: ${it.description}" }

    /** Text returned by load_skill, or a short error naming the valid choices. */
    fun load(name: String): Result<String> {
        val doc = find(name)
            ?: return Result.failure(IllegalArgumentException("Unknown skill '$name'. Available: ${names.joinToString()}"))
        return Result.success("SKILL ${doc.name}\n\n${doc.body}")
    }
}
