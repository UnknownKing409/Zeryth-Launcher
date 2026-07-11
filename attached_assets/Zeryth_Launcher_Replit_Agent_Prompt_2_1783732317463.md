# Task

Add a dedicated **File Manager** button/icon to the launcher's main screen.

The new shortcut should be placed **beside the Multiplayer icon/button**.

---

# Placement

Locate the row or section that currently contains the Multiplayer shortcut.

Insert a new File Manager shortcut immediately beside it.

The placement should feel intentional and balanced.

Do **NOT** make the layout appear crowded or uneven.

If spacing adjustments are required, adjust the surrounding layout so everything remains visually consistent.

---

# Behavior

When the new File Manager button is pressed:

- Open Zeryth Launcher's built-in File Manager immediately.
- Do **NOT** open Android's system file picker.
- Do **NOT** open any external file manager applications.
- Launch the same built-in File Manager that is already used elsewhere in the launcher.

Reuse the existing File Manager implementation.

Do **NOT** create another File Manager activity or duplicate navigation logic.

---

# Icon & Design

Use an icon that clearly represents file management, such as a folder or similar symbol already used by the launcher.

The new button should:

- Match the size of the surrounding main screen buttons.
- Match the same padding.
- Match the same margins.
- Match the same corner radius (if applicable).
- Match the same animation behavior.
- Match the same ripple/click feedback.
- Match the current Zeryth Launcher design language.

The new shortcut should look like it has always been part of the launcher.

---

# Navigation

Reuse the existing navigation flow.

The new shortcut should simply navigate to the built-in File Manager using the existing implementation.

Avoid:

- Duplicate activities.
- Duplicate fragments.
- Duplicate navigation code.
- Temporary workarounds.
- Hardcoded navigation.

---

# Existing Architecture

Before making changes:

- Locate how the File Manager is currently opened.
- Reuse the existing navigation method.
- Reuse the existing icons where appropriate.
- Reuse existing UI components.

Keep the implementation clean and modular.

---

# Code Quality

Before committing:

- Do not redesign the main screen.
- Only add the new shortcut.
- Keep spacing consistent.
- Preserve the current visual hierarchy.
- Follow the project's coding style.

---

# Project Cleanliness

Do **NOT** create any `attached_assets` containing:

- Zeryth Launcher Replit Agent prompt documentation
- Zeryth Launcher Replit Agent prompt templates
- Any similar prompt-related documentation

Do **NOT** create any folders or directories related to those prompt files.

These instructions are only for the current Replit session and must never become part of the repository.