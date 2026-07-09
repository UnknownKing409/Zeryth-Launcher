- Renderer system
- File Manager
- Settings
- Navigation
- Any existing Zeryth Launcher functionality
This task should only change the default visual state of the Task Menu after minimizing a task.
---
# Code Quality
Before making changes:
- Locate where the Task Menu expansion state is initialized.
- Modify only the logic responsible for the initial expanded/collapsed state.
- Reuse the existing state management system.
- Avoid duplicate implementations.
- Avoid introducing special-case logic for different download types.
- Keep the implementation clean, modular, and maintainable.
---
# Testing Requirements
Before considering the task complete, verify that:
- Minimizing a Minecraft version installation creates a minimized Task Menu.
- Minimizing a mod installation creates a minimized Task Menu.
- Minimizing a modpack installation creates a minimized Task Menu.
- Minimizing a Resource Pack installation creates a minimized Task Menu.
- Minimizing a Shader installation creates a minimized Task Menu.
- Users can still manually expand the Task Menu.
- Progress continues updating correctly while minimized.
- Clicking tasks still restores the original progress popup.
- Multiple simultaneous tasks continue working correctly.
- Existing functionality remains unchanged.
- The project builds successfully.
- A Release APK is generated successfully.
---
# Final Report
Before finishing, provide a report including:
- Which files were modified.
- How the default Task Menu state was changed.
- Whether any existing state management was reused.
- Any compatibility adjustments that were required.
- Confirmation that only the default expansion state was modified.
- Confirmation that all existing Task Menu functionality remains intact.
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