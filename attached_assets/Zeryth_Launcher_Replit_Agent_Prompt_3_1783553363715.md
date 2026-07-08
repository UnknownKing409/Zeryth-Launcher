- Any existing Zeryth Launcher functionality
This task should only implement the feature from Issue #40 while preserving all existing functionality.
---
# Code Quality
Before making changes:
- Copy the implementation directly from Zalith Launcher+.
- Preserve its structure whenever possible.
- Adapt only compatibility code.
- Avoid unnecessary refactoring.
- Avoid introducing duplicate logic.
- Keep the implementation maintainable.
Do **NOT** replace the copied implementation with a rewritten version unless absolutely required for compatibility.
---
# Testing Requirements
Before considering the task complete, verify that:
- The feature behaves exactly like Zalith Launcher+.
- The only intentional difference is that the "X" button remains.
- Existing functionality continues working.
- No regressions have been introduced.
- The project builds successfully.
- A Release APK is generated successfully.
---
# Final Report
Before finishing, provide a report including:
- Which files were copied from Zalith Launcher+.
- Which files required compatibility changes.
- What compatibility adjustments were necessary.
- Confirmation that the implementation matches Zalith Launcher+.
- Confirmation that the only intentional deviation is keeping the "X" button.
- Confirmation that no unrelated functionality was changed.
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