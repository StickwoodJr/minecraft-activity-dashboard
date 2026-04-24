# Contributing

We welcome contributions to the Minecraft Activity Dashboard! To ensure a smooth collaboration process, please adhere to the following guidelines.

## Branch Naming Convention
Please use descriptive branch names that reflect the nature of your changes. We recommend the following prefixes:
- `feature/` for new features (e.g., `feature/dark-mode`)
- `fix/` for bug fixes (e.g., `fix/hourly-graph-timezone`)
- `docs/` for documentation updates
- `refactor/` for structural code changes

## Pull Request Guidelines
1. **Keep it focused:** Ensure your PR addresses a single issue or feature.
2. **Describe your changes:** Provide a clear summary of what you changed and why. If it fixes an open issue, link to it.
3. **Update documentation:** If you add a new feature or change an existing one, update the relevant documentation.
4. **Test your code:** Verify that the project builds successfully and runs locally without errors.

## Testing Locally
To test your changes, you should build the mod using Gradle from the `backend/` directory:

```bash
cd backend/
./gradlew build
```

This ensures the `syncWebAssets` task runs, correctly packaging any frontend changes from `frontend/` into the mod's resources. After building, drop the compiled `.jar` file into a local Fabric test server's `mods` directory and verify the dashboard behavior in your browser.
