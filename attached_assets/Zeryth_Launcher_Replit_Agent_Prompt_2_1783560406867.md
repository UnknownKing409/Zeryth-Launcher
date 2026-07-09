- Progress tracking
- Background task execution
- Task restoration
- Task notifications
- Task lifecycle
- Task queue
- Multiple simultaneous tasks
- Download progress
- Installation progress
- Failure handling
- Retry handling
- Completion handling
Only the initial visual state should change.
---
# Existing Architecture
Reuse the existing Task Menu implementation.
Do **NOT** create:
- Another Task Menu
- Another minimized state
- Duplicate task containers
- Duplicate animations
- Duplicate state managers
Simply modify the existing logic that determines the initial expansion state when a task is minimized.
---
# Compatibility
The behavior should apply consistently to:
- Minecraft version downloads
- Mod downloads
- Modpack downloads
- Resource Pack downloads
- Shader downloads
- Future download/install tasks that use the same Task Menu infrastructure
The implementation should automatically apply to every supported task instead of requiring task-specific implementations.
---
# Preserve Existing Functionality
Do **NOT** break:
- Background Task Manager
- Download Manager
- Version installation
- Mod installation
- Modpack installation
- Resource Pack installation
- Shader installation
- Task restoration
- Task progress updates
- Notifications
- Launcher startup
- Game launching
- Microsoft Login
- Offline Login
- Ely.by Login
- Legacy (Zalith 1) controls
- Zalith 2 controls