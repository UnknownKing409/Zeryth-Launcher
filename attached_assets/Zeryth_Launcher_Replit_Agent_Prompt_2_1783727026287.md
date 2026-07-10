# Task

Update `.github/workflows/build.yml`.

Current behavior:
- APKs are uploaded using wildcard paths.
- Multiple APKs are grouped into artifact archives.

Required behavior:
- Upload every generated APK as its own artifact.
- Each artifact must contain exactly one APK.
- The artifact name must exactly match the APK filename, including the `.apk` extension.
- Automatically detect generated APK filenames whenever possible instead of hardcoding version numbers.
- Preserve `compression-level: 0`.
- Use the official `actions/upload-artifact@v4`.
- Keep Release and Debug workflows working.
- Ensure compatibility with every architecture produced by the matrix (Universal, arm64-v8a, armeabi-v7a, x86, x86_64, or any future variants).

The final GitHub Actions Artifacts page should display each APK individually, similar to the Zalith Launcher Plus workflow, instead of a single ZIP artifact containing multiple APKs.