**Do NOT remove the existing "X" (close) button.**
Keep:
- Its position.
- Its visibility.
- Its functionality.
- Its behavior.
Everything else should match Zalith Launcher+ exactly.
---
# Compatibility
If the copied implementation depends on APIs or classes that differ from Zeryth Launcher:
- Adapt only those integration points.
- Preserve the original implementation as much as possible.
- Do **NOT** redesign the feature.
Only modify what is strictly necessary to make it compile and work with the latest Main branch.
---
# Existing Architecture
Reuse the copied implementation instead of creating new systems.
Avoid:
- Duplicate implementations.
- Parallel systems.
- Temporary workarounds.
- Rewriting existing Zalith Launcher+ logic.
The implementation should remain as close as possible to the original while integrating cleanly into Zeryth Launcher.
---
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
- Background task manager
- Resource Pack management
- Shader management
- Settings
- Navigation
- Themes
- User preferences