# Requirements

- Inspect the existing workflow before making modifications.
- Change only what is required for artifact uploading.
- Do not modify Gradle tasks.
- Do not modify signing.
- Do not modify the matrix build strategy.
- Do not break existing CI behavior.
- Validate the workflow before committing.
- Ensure every generated APK is uploaded as its own artifact.
- Avoid hardcoding version numbers when naming artifacts.
- Keep the workflow maintainable and future-proof.

Commit Strategy:
- Every intermediate commit must include `[skip ci]`.
- Only the final commit should trigger GitHub Actions by omitting `[skip ci]`.

Do not finish until:
- The workflow validates successfully.
- GitHub Actions builds successfully.
- Every APK appears as its own downloadable artifact in the Artifacts section.
- No ZIP artifact contains multiple APKs.
```