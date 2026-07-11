# Preserve Existing Functionality

Do **NOT** break:

- GitHub Actions workflow triggers
- APK builds
- Release APK builds
- Artifact uploads
- Artifact naming
- GitHub Releases (if applicable)
- Signing process
- Branch-specific workflows
- Pull Request workflows
- Manual workflow dispatch
- Build caching
- Gradle configuration
- Existing CI automation
- Any existing Zeryth Launcher functionality

The workflows should behave exactly the same after modernization.

---

# Testing Requirements

Before considering the task complete, verify that:

- Every workflow validates successfully.
- Every GitHub Actions deprecation warning that can legitimately be fixed has been resolved.
- All Actions use the latest stable supported versions where appropriate.
- Deprecated syntax has been removed.
- Deprecated workflow commands have been replaced.
- CI continues building successfully.
- Release APKs are generated successfully.
- APK artifacts continue uploading successfully.
- No regressions have been introduced.
- Any remaining warnings are documented with their official cause.

---

# Final Report

Before finishing, provide:

- Every workflow that was audited.
- Every Action version updated.
- Every deprecated syntax replaced.
- Every warning resolved.
- Every warning that could not be resolved.
- The reason each unresolved warning remains.
- Confirmation that CI behavior was preserved.
- Confirmation that APK builds and uploads still function correctly.

---

# FINAL BUILD REQUIREMENT (MANDATORY)

Do **NOT** finish the session until every GitHub Actions workflow validates successfully and a final Release APK has been successfully built and uploaded through CI.

If any workflow fails:

1. Read the workflow logs.
2. Identify the root cause.
3. Fix the issue.
4. Validate the workflow again.
5. Repeat until the workflows complete successfully.

Do not stop after modifying the workflow files.

The task is only complete when CI successfully builds and uploads the Release APK.

---

# Commit Strategy

Use `[skip ci]` for every intermediate commit.

Only the final commit should omit `[skip ci]` so GitHub Actions runs once to validate the final workflows and generate the Release APK.