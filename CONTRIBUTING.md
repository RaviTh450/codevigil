# Contributing to CodeVigil

Thank you for your interest in contributing to CodeVigil! This guide outlines the process and expectations for contributions.

## Getting Started

1. **Fork** the repository on GitHub.
2. **Clone** your fork locally:
   ```bash
   git clone https://github.com/<your-username>/codevigil.git
   ```
3. **Create a branch** from `main` for your work:
   ```bash
   git checkout -b feature/your-feature-name
   ```
4. Make your changes, commit, and push to your fork.
5. **Open a Pull Request** against the `main` branch of this repository.

## Code Style

- This project is written in **Kotlin**. Follow existing conventions found in the codebase.
- Keep code clean, readable, and well-documented.
- Avoid introducing unnecessary dependencies.

## Pull Request Requirements

- All PRs **must pass CI** before merging:
  ```bash
  ./gradlew test
  ```
- **Signed commits are required.** Configure commit signing with GPG or SSH keys. Unsigned commits will be rejected.
- **No direct pushes to `main`.** All changes must go through a pull request and code review.
- Keep PRs focused. One logical change per PR.
- Include a clear description of what your PR does and why.

## Reporting Issues

We provide issue templates to help you file clear reports:

- **Bug Report** -- Use the bug report template for defects, crashes, or unexpected behavior.
- **Feature Request** -- Use the feature request template for new ideas or enhancements.

Please search existing issues before opening a new one to avoid duplicates.

## Code of Conduct

All contributors are expected to follow our [Code of Conduct](CODE_OF_CONDUCT.md). Please read it before participating.

## Questions?

If you have questions about contributing, feel free to open a discussion or reach out via an issue.
