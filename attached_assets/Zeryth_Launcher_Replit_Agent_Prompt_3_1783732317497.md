# Preserve Existing Functionality

Do **NOT** break:

- Launcher startup
- Home screen layout
- Play button
- Accounts
- News
- Multiplayer shortcut
- Renderer system
- File Manager
- Downloads
- Microsoft Login
- Offline Login
- Ely.by Login
- Legacy (Zalith 1) controls
- Zalith 2 controls
- Settings
- Navigation
- Themes
- Any existing Zeryth Launcher functionality

This task should only add a dedicated File Manager shortcut to the main screen.

---

# Testing Requirements

Before considering the task complete, verify that:

- A new File Manager shortcut appears beside the Multiplayer button.
- The layout remains visually balanced.
- Pressing the shortcut opens the built-in File Manager immediately.
- No external file picker or third-party file manager is launched.
- Existing main screen buttons continue working correctly.
- Existing File Manager functionality remains unchanged.
- No regressions have been introduced.
- The project builds successfully.
- A Release APK is generated successfully.

---

# Final Report

Before finishing, provide:

- Which UI files were modified.
- Which navigation components were reused.
- How the File Manager shortcut was integrated.
- Confirmation that the shortcut uses the existing built-in File Manager.
- Confirmation that no existing functionality was broken.

---

# FINAL BUILD REQUIREMENT (MANDATORY)

Do **NOT** finish the session until a final Release APK has been successfully built.

If the APK fails to build:

1. Read every compiler error.
2. Fix every issue.
3. Build again.
4. Repeat until a successful Release APK has been generated.

Do not stop after writing code.

The task is only complete when the final Release APK builds successfully.

---

# Commit Strategy

Use `[skip ci]` for every intermediate commit.

Only the final commit should omit `[skip ci]` so GitHub Actions runs once to validate the final build and generate the Release APK.