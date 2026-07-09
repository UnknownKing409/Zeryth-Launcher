# Preserve Existing Functionality

Do **NOT** break:

- Launcher startup
- Game launching
- Microsoft Login
- Offline Login
- Ely.by Login
- Account management
- Legacy (Zalith 1) controls
- Zalith 2 controls
- Renderer system
- File Manager
- Downloads
- CurseForge integration
- Modrinth integration
- Background Task Manager
- Resource Pack management
- Shader management
- Settings
- Navigation
- Themes
- User preferences
- Any existing Zeryth Launcher functionality

Only replace the implementation described in Issue #36.

Everything else should continue working normally.

---

# Testing Requirements

Before considering the task complete, verify that:

- The implementation now behaves like Zalith Launcher+'s implementation.
- Every requested feature from Issue #36 works correctly.
- Every reported bug from Issue #36 has been fixed.
- Every reported crash from Issue #36 has been fixed.
- Every reported regression has been resolved.
- Existing launcher functionality continues working correctly.
- No new regressions have been introduced.
- The implementation integrates cleanly with the latest Main branch.
- The project builds successfully.
- A Release APK is generated successfully.

---

# Final Report

Before finishing, provide:

- Which Zalith Launcher+ files were copied or used as the basis.
- Which Zeryth Launcher files were replaced.
- Which compatibility changes were required.
- Which bugs and crashes were fixed.
- Confirmation that the previous implementation has been completely replaced.
- Confirmation that the new implementation matches Zalith Launcher+'s behavior as closely as possible.

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

Only the final commit should omit `[skip ci]` so GitHub Actions runs once to build the final Release APK.