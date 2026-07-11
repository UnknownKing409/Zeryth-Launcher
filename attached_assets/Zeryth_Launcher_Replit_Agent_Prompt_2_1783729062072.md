# Task

Perform a full audit of every GitHub Actions workflow used by Zeryth Launcher.

The audit should include every workflow file, every action, every reusable workflow, every script, and every CI configuration currently used by the project.

---

# Workflow Audit

Inspect every workflow for:

- GitHub deprecation warnings
- Deprecated Actions versions
- Deprecated workflow commands
- Deprecated syntax
- Deprecated environment handling
- Deprecated output handling
- Deprecated state handling
- Deprecated runner behavior
- Deprecated upload/download implementations
- Deprecated cache implementations
- Deprecated artifact implementations
- Deprecated permissions usage
- Deprecated checkout implementations
- Deprecated Java setup implementations
- Deprecated Android build workflow practices

Read GitHub's latest official documentation whenever necessary before making changes.

---

# Modernization

Update every workflow to the latest stable officially supported versions wherever possible.

Examples include:

- GitHub Actions versions
- Upload Artifact
- Download Artifact
- Checkout
- Setup Java
- Cache
- Gradle-related actions
- Android build actions
- Official GitHub workflow syntax

Only use officially supported releases.

Do **NOT** migrate to beta, preview, experimental, or unofficial Actions.

---

# Preserve Existing Behavior

Modernization must **NOT** change how CI currently behaves.

Preserve:

- APK generation
- Release APK generation
- Artifact names
- Artifact structure
- Build matrix
- Build triggers
- Manual dispatch
- Push triggers
- Pull Request triggers
- Branch filtering
- Build outputs
- Signing behavior
- Release behavior
- Upload behavior
- Existing automation

The goal is to modernize the implementation—not the behavior.

---

# CI Warnings

Resolve every warning that can legitimately be fixed.

Examples include:

- Deprecated Actions
- Deprecated commands
- Deprecated syntax
- Deprecated APIs
- Deprecated environment variables
- Deprecated outputs
- Deprecated state files

If a warning originates from:

- GitHub infrastructure
- GitHub-hosted runners
- The latest official version of an Action
- An upstream dependency that has no supported replacement

Do **NOT** create unsupported workarounds simply to hide the warning.

Instead:

- Leave the implementation on the latest supported approach.
- Document why the warning cannot currently be eliminated.

---

# Validation

After completing the modernization:

Validate every workflow.

Ensure:

- YAML syntax is valid.
- Workflow logic remains unchanged.
- Triggers still work.
- Jobs execute successfully.
- Artifacts upload successfully.
- APKs build successfully.
- No new warnings were introduced.

---

# Code Quality

Before committing:

- Keep workflows clean.
- Remove obsolete syntax.
- Remove deprecated commands.
- Remove unnecessary duplication where appropriate.
- Preserve readability.
- Follow GitHub Actions best practices.
- Use only officially documented features.

Do **NOT** redesign the CI pipeline unless required by deprecation fixes.

---

# Documentation

If any warning cannot be resolved:

Document:

- Which workflow produces it.
- The exact warning.
- Why it cannot currently be resolved.
- The official limitation preventing the fix.
- Why no supported workaround exists.

Do **NOT** implement hacks merely to silence CI warnings.

---

# Project Cleanliness

Do **NOT** create any `attached_assets` containing:

- Zeryth Launcher Replit Agent prompt documentation
- Zeryth Launcher Replit Agent prompt templates
- Any similar prompt-related documentation

Do **NOT** create any folders or directories related to those prompt files.

These instructions are only for the current Replit session and must never become part of the repository.