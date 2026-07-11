# GitHub Actions Modernization & CI Audit

## Repository

Primary Repository:
https://github.com/ZerythLauncher/Zeryth-Launcher

Primary Branch:
`Zeryth-Main`

---

# Repository Notice

The previous Zeryth Launcher repository has been **archived**.

Development has officially moved to:

https://github.com/ZerythLauncher/Zeryth-Launcher

The new repository contains the same codebase as the archived repository along with newer fixes, features, and improvements.

Treat the new repository as the authoritative source for all development.

---

# Objective

Perform a comprehensive audit and modernization of **every GitHub Actions workflow** in the repository.

The objective is to eliminate CI deprecation warnings, modernize workflows to current GitHub standards, and improve long-term maintainability **without changing the existing CI behavior**.

The existing workflows are already functional. This task is **not** about redesigning the CI pipeline.

Instead, this task focuses on ensuring that every workflow remains compatible with GitHub's latest supported standards while preserving the existing build process and outputs.

---

# Scope

Audit every workflow located under:

`.github/workflows/`

Review every reusable action, every workflow step, every workflow command, and every deprecated syntax currently being used.

Only modernize workflows where improvements are officially supported.

Do **not** introduce unofficial workarounds merely to silence warnings.