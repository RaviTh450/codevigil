# Code Pattern Analyzer

**The #1 Architecture Guardian for AI-Assisted Development**

[![Build](https://github.com/RaviTh450/code-pattern-analyzer/actions/workflows/build.yml/badge.svg)](https://github.com/RaviTh450/code-pattern-analyzer/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![JetBrains Plugin](https://img.shields.io/badge/JetBrains-Plugin-orange)](https://plugins.jetbrains.com/plugin/com.codepattern.analyzer)

The most comprehensive **open-source** code quality and architecture analysis tool. Works as a JetBrains plugin, LSP server (VS Code, Vim, Emacs), and CLI for CI/CD. Acts as a **real-time rulebook** for AI coding agents (Claude, Copilot, Cursor) — catches architectural violations, security flaws, and code smells **as code is written**.

---

## 19 Analyzers in One Tool

| Category | What it Detects |
|----------|----------------|
| **Architecture** | Layer dependency violations across 12 patterns (MVC, Clean Architecture, SOLID, DDD, Hexagonal, CQRS, Microservices, Layered, Repository, Observer, Factory, Code Quality) |
| **Complexity** | Cyclomatic (McCabe 1976), Cognitive (SonarSource 2017), Big-O time/space estimation per function |
| **Security** | OWASP Top 10: SQL injection, XSS, hardcoded secrets, command injection, weak crypto, path traversal, XXE — 14 vulnerability types with CWE IDs |
| **Memory & GC** | Resource leaks, unbounded caches, string concat in loops, ThreadLocal leaks, GC pressure, allocation in hot loops |
| **Thread Safety** | Race conditions, deadlock risk (nested locks), missing volatile, coroutine blocking calls, TOCTOU |
| **Code Smells** | God Class, Feature Envy, Long Parameter List, Data Class smell |
| **Dead Code** | Unused imports, unreachable code, empty catch blocks, commented-out code, unused private methods |
| **Duplicate Code** | Copy-paste detection via token hashing across files |
| **Naming** | Language-aware convention checking (PascalCase, camelCase, snake_case) |
| **API Contracts** | REST endpoint validation: missing auth, validation, error handling, API docs |
| **Call Graph** | Function path visualization, hub/orphan detection (ASCII, Mermaid, DOT) |
| **Circular Deps** | Circular dependency detection across the project |
| **Coupling** | Afferent/efferent coupling with instability metrics |

## Quick Start

### JetBrains IDE (IntelliJ, WebStorm, PyCharm, etc.)

1. **Install**: Settings > Plugins > Marketplace > Search "Code Pattern Analyzer"
2. **Open any project** — pattern is auto-detected from folder structure
3. **Violations appear inline** as you type with confidence scores
4. **Tools > Code Patterns** for full project scans

### CLI (CI/CD, any terminal)

```bash
# Full automated code review
java -jar code-pattern-analyzer.jar --review ./my-project

# Quick start: generate .codepattern.md for AI agents
java -jar code-pattern-analyzer.jar --init ./my-project

# Specific analyses
java -jar code-pattern-analyzer.jar --security --dead-code --duplicates ./my-project

# CI/CD with SARIF output for GitHub Code Scanning
java -jar code-pattern-analyzer.jar --review --format sarif --output results.sarif ./my-project
```

### VS Code / Vim / Emacs / Sublime (LSP)

```bash
# Start the LSP server
java -jar code-pattern-analyzer.jar --lsp

# VS Code: add to settings.json
{
  "languageserver": {
    "codepattern": {
      "command": "java",
      "args": ["-jar", "code-pattern-analyzer.jar", "--lsp"]
    }
  }
}
```

## AI Agent Integration

This is what makes Code Pattern Analyzer go viral: **AI agents read `.codepattern.md` as their architecture rulebook.**

```bash
# Step 1: Initialize your project
java -jar code-pattern-analyzer.jar --init ./my-project

# This creates:
#   .codepattern.md     — AI agents read this automatically (commit to git)
#   .codepattern.yml    — Configuration
#   .git/hooks/pre-commit (with --install-hook)
#   .github/workflows/  — Automated PR reviews
```

### How it works:
1. You run `--init` → `.codepattern.md` appears in your repo
2. AI agent (Claude, Cursor, Copilot) reads it as context
3. AI follows the architecture rules while coding
4. Pre-commit hook catches violations before they land
5. GitHub Action reviews PRs automatically
6. **You tell other devs → adoption spreads**

### AI Fast-Check Mode
```bash
# Only review what changed (fast feedback for AI agents)
java -jar code-pattern-analyzer.jar --diff --format json ./my-project
```

## CLI Reference

```
Usage: java -jar code-pattern-analyzer.jar [options] <project-path>

Analysis:
  --pattern <name>       Validate against: mvc, clean-architecture, solid, ddd,
                         hexagonal, cqrs, microservices, layered, repository,
                         observer, factory, code-quality
  --all-patterns         Run all 12 patterns
  --review               Full automated code review (PASS/WARN/FAIL)
  --complexity           Cyclomatic + cognitive complexity
  --bigo                 Big-O estimation per function
  --security             OWASP Top 10 security scan
  --dead-code            Unused imports, unreachable code, empty catch
  --duplicates           Copy-paste detection
  --naming               Naming convention check
  --api                  REST API contract validation
  --memory               Memory leaks, GC pressure
  --threads              Thread safety, race conditions
  --circular-deps        Circular dependency detection
  --coupling             Coupling analysis

Output:
  --format text|json|html|sarif   Output format (default: text)
  --output <file>                 Write to file
  --fail-on error|warning         Exit code 1 on violations (for CI)

AI & Setup:
  --init                 Generate .codepattern.md + hooks + GitHub Action
  --diff                 Review only git-changed files
  --install-hook         Install pre-commit hook
  --watch                Continuous watch mode

Advanced:
  --fitness              Architecture fitness functions
  --save-baseline        Save architecture baseline
  --drift                Compare against baseline
  --graph ascii|mermaid|dot       Dependency graph
  --call-graph ascii|mermaid|dot  Function call graph
  --incremental          Only scan changed files
```

## Code Review Gate

The `--review` command runs all 12 analysis categories and returns a verdict:

```
+==========================================+
|       CODE REVIEW GATE - VERDICT         |
+==========================================+
|  PASS                                    |
|  Score: 92/100                           |
+==========================================+

[PASS] Architecture - No violations
[PASS] Complexity - All functions within bounds
[PASS] Security - No vulnerabilities detected
[WARN] Dead Code - 3 unused imports
[PASS] Thread Safety - No issues
```

JSON output for AI agents:
```json
{
  "verdict": "PASS",
  "score": 92,
  "totalViolations": 3,
  "categories": [...]
}
```

## Supported Languages

Java, Kotlin, Python, TypeScript, JavaScript, Go, Rust, C#, Swift, Dart, Ruby, PHP, Scala, C/C++, and more.

## Building from Source

```bash
# Build the IntelliJ plugin
./gradlew buildPlugin
# Output: build/distributions/code-pattern-analyzer-1.0.0.zip

# Build the CLI JAR
./gradlew cliJar
# Output: build/libs/code-pattern-analyzer-1.0.0-cli.jar

# Run tests (126 tests)
./gradlew test
```

## Contributing

Contributions welcome! Please:
1. Fork the repo
2. Create a feature branch
3. Run `./gradlew test` (all 126 tests must pass)
4. Submit a PR

## License

Apache License 2.0 - see [LICENSE](LICENSE)

---

**Built by [Ravi Thakur](https://github.com/RaviTh450)** | [Report Issues](https://github.com/RaviTh450/code-pattern-analyzer/issues)
