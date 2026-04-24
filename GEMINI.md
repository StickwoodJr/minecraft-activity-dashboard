# Project Description
Minecraft server activity dashboard — Java Fabric mod backend with an embedded HTTP server serving JSON and a vanilla HTML/JS frontend heatmap.

# Code Conventions
- **Java**: Standard Java formatting, Javadoc for public classes and endpoints
- **JS/HTML**: Prettier formatting, no inline styles, semantic HTML
- **Filenames**: kebab-case for all new frontend and documentation files

# Folder Conventions
- `frontend/` — all HTML, CSS, JS single-source-of-truth assets
- `backend/` — Java Fabric mod source, build.gradle, log parser, web server
- `docs/` — README, ARCHITECTURE, CONTRIBUTING

# Git Conventions
- Conventional commits: feat / fix / chore / docs / refactor
- Feature branches for any change touching more than one file
- No direct push to main for behavior changes; open a PR instead
