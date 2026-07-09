# Task

Completely **reimplement/rewrite** the feature described in **Issue #36**.

Do **NOT** continue building on the current implementation.

Do **NOT** patch or extend the existing implementation.

Instead, replace it with the implementation used in **Zalith Launcher+**.

---

# IMPORTANT IMPLEMENTATION METHOD

I do **NOT** want a recreation of the feature.

I do **NOT** want a redesign.

I do **NOT** want an interpretation.

I want the **actual implementation** from Zalith Launcher+.

Locate the implementation in the Zalith Launcher+ repository and use it as the foundation for this rewrite.

Copy:

- UI
- Layout
- Workflow
- Logic
- State handling
- Animations
- Behaviors
- Supporting classes
- Supporting utilities

Adapt only what is strictly necessary for compatibility with Zeryth Launcher.

The implementation should remain as close as possible to Zalith Launcher+.

---

# Analyze Before Implementing

Before writing any code:

1. Read the entire Issue #36.
2. Read every comment.
3. Review every screenshot, attachment, and example.
4. Locate the implementation in Zalith Launcher+.
5. Identify every related class and file.
6. Understand how the implementation works before copying it.
7. Replace the existing implementation with the copied implementation.

Do **NOT** blindly copy code.

Understand it first, then integrate it correctly.

---

# Rewrite Strategy

Treat this as a complete replacement.

That means:

- Remove obsolete logic.
- Remove obsolete UI.
- Remove obsolete state handling.
- Remove obsolete helper classes if they are no longer needed.
- Replace them with the Zalith Launcher+ implementation.

Avoid mixing the previous implementation with the new one.

The final codebase should contain a **single** implementation.

---

# Bug & Crash Fixes

As part of this rewrite:

- Fix every bug described in Issue #36.
- Fix every crash described in Issue #36.
- Fix every regression described in Issue #36.
- Fix any additional issue discovered while replacing the implementation.

Do **NOT** simply hide symptoms.

Identify and resolve the root cause of every issue.

---

# Compatibility

If the copied implementation depends on APIs or classes that differ from Zeryth Launcher:

- Adapt only the required integration points.
- Preserve the copied implementation as much as possible.
- Do not redesign the feature.
- Avoid introducing wrappers unless absolutely necessary.

The final implementation should still closely resemble the Zalith Launcher+ implementation.

---

# Existing Architecture

Reuse the copied implementation.

Avoid:

- Duplicate systems.
- Duplicate UI.
- Parallel implementations.
- Temporary workarounds.
- Rewritten replacements.

Keep the implementation modular and maintainable.

---

# Code Quality

Before committing:

- Remove obsolete code from the previous implementation.
- Remove unused classes.
- Remove duplicate logic.
- Preserve clean architecture.
- Follow the existing project style.
- Keep the implementation maintainable.

The finished implementation should feel like Zalith Launcher+'s implementation naturally integrated into Zeryth Launcher.