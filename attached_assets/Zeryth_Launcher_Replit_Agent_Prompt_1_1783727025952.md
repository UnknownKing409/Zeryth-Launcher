# Context

The old Zeryth Launcher repository has been archived. The project has moved to the new repository:

https://github.com/ZerythLauncher/Zeryth-Launcher

Use the `Zeryth-Main` branch as the primary development branch.

Inspect the repository before making any changes. Reuse the existing architecture, build system, and coding patterns instead of replacing them. Maintain compatibility with the current Material 3 UI and project structure.

The current GitHub Actions workflow successfully builds APKs, but the generated artifacts are not presented in the desired format.

Goal:
Make the GitHub Actions Artifacts page behave like the Zalith Launcher Plus workflow, where every generated APK appears as its own downloadable artifact instead of being grouped into ZIP artifacts.

Do not change the build logic, Gradle configuration, signing configuration, or matrix build strategy unless absolutely necessary for the artifact upload process.