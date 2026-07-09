Continue working on the Zeryth Launcher project using the latest code from the Main branch.
Repository:
https://github.com/johnrenonasuncion-Ramil/Zeryth-Launcher
Issue Reference (ONLY implement this feature request):
https://github.com/johnrenonasuncion-Ramil/Zeryth-Launcher/issues/46
---
# Task
I want you to implement the feature suggestion described in **Issue #46**.
The goal is to improve the user experience of the background task system by changing the **default state** of the Task Menu whenever a download or installation task is minimized.
Currently, when minimizing supported download/install tasks, the Task Menu is expanded by default.
Instead, I want the Task Menu to be **minimized by default**, matching the behavior requested in Issue #46.
---
# Desired Behavior
Whenever the user minimizes one of the supported download/install popups:
- Minecraft version installation
- Mod installation
- Modpack installation
- Resource Pack installation
- Shader installation
- Any other download/install task integrated into the Task Menu
The task should continue running exactly as it does now.
However...
Instead of automatically showing the **expanded Task Menu**, it should create the task in the **collapsed/minimized Task Menu state**.
The user can manually expand the Task Menu whenever they want to monitor progress.
---
# IMPORTANT
This task is **NOT** asking you to redesign the Task Menu.
It is **NOT** asking you to change its functionality.
It is **ONLY** changing the default state after minimizing a task.
Everything else about the Task Menu should remain exactly the same.
---
# Current Workflow
Current behavior:
Download Popup
↓
User presses **Minimize**
↓
Expanded Task Menu appears automatically
↓
User manually minimizes it again
---
# Desired Workflow
Download Popup
↓
User presses **Minimize**
↓
Task is added directly into the **already minimized/collapsed Task Menu**
↓
User can manually expand it later if they want.
This eliminates an unnecessary interaction and provides a cleaner experience.
---
# Preserve Existing Functionality
Changing the default state must **NOT** affect: