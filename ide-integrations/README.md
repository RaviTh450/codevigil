# Cross-IDE Setup Guide

CodeVigil works across all major IDEs via:
1. **JetBrains IDEs** — Native plugin (IntelliJ IDEA, WebStorm, PyCharm, GoLand, Android Studio, etc.)
2. **VS Code** — Extension using LSP server
3. **Vim / Neovim** — Via nvim-lspconfig
4. **Emacs** — Via lsp-mode
5. **Sublime Text** — Via LSP package
6. **CLI** — For any environment and CI/CD pipelines

---

## JetBrains IDEs (Native Plugin)

Install from JetBrains Marketplace or build from source:

```bash
./gradlew buildPlugin
# Install: Settings > Plugins > Install from Disk > build/distributions/codevigil-1.0.0.zip
```

Supported: IntelliJ IDEA, WebStorm, PyCharm, GoLand, Android Studio, Rider, PhpStorm, RubyMine, CLion, DataGrip, Fleet.

---

## VS Code

1. Build the CLI JAR:
   ```bash
   ./gradlew cliJar
   ```
2. Copy `build/libs/codevigil-1.0.0-cli.jar` to `ide-integrations/vscode/server/`
3. Install the extension:
   ```bash
   cd ide-integrations/vscode
   npm install && npm run compile
   code --install-extension .
   ```

---

## Vim / Neovim (nvim-lspconfig)

Add to your `init.lua`:

```lua
local lspconfig = require('lspconfig')
local configs = require('lspconfig.configs')

if not configs.codepattern then
  configs.codepattern = {
    default_config = {
      cmd = { 'java', '-jar', '/path/to/codevigil-1.0.0-cli.jar', '--lsp' },
      filetypes = { 'java', 'kotlin', 'python', 'typescript', 'javascript', 'go', 'rust', 'cs', 'swift', 'dart', 'ruby', 'php' },
      root_dir = lspconfig.util.root_pattern('.git', 'build.gradle', 'pom.xml', 'package.json', 'Cargo.toml', 'go.mod'),
      settings = {},
    },
  }
end

lspconfig.codepattern.setup{}
```

---

## Emacs (lsp-mode)

Add to your Emacs config:

```elisp
(require 'lsp-mode)

(lsp-register-client
 (make-lsp-client
  :new-connection (lsp-stdio-connection '("java" "-jar" "/path/to/codevigil-1.0.0-cli.jar" "--lsp"))
  :major-modes '(java-mode kotlin-mode python-mode typescript-mode js-mode go-mode rust-mode csharp-mode swift-mode dart-mode ruby-mode php-mode)
  :server-id 'codevigil))
```

---

## Sublime Text (LSP Package)

Install the [LSP package](https://packagecontrol.io/packages/LSP), then add to LSP settings:

```json
{
  "clients": {
    "codevigil": {
      "command": ["java", "-jar", "/path/to/codevigil-1.0.0-cli.jar", "--lsp"],
      "selector": "source.java | source.kotlin | source.python | source.ts | source.js | source.go | source.rust | source.cs | source.swift | source.dart | source.ruby | source.php",
      "enabled": true
    }
  }
}
```

---

## CLI (CI/CD Integration)

```bash
# Build CLI JAR
./gradlew cliJar

# Basic scan
java -jar codevigil-1.0.0-cli.jar --pattern clean-architecture ./my-project

# CI/CD: fail build on errors
java -jar codevigil-1.0.0-cli.jar --pattern solid --fail-on error ./my-project

# Full analysis with HTML report
java -jar codevigil-1.0.0-cli.jar --all-patterns --complexity --circular-deps --format html --output report.html ./my-project

# JSON output for automated processing
java -jar codevigil-1.0.0-cli.jar --pattern mvc --format json --output results.json ./my-project
```

### GitHub Actions Example

```yaml
name: Architecture Check
on: [push, pull_request]
jobs:
  pattern-check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'
      - name: Download CodeVigil
        run: |
          wget https://github.com/codepattern/codevigil/releases/latest/download/codevigil-cli.jar
      - name: Run Architecture Check
        run: |
          java -jar codevigil-cli.jar \
            --pattern clean-architecture \
            --fail-on error \
            --format json \
            --output pattern-report.json \
            .
      - name: Upload Report
        uses: actions/upload-artifact@v4
        if: always()
        with:
          name: pattern-report
          path: pattern-report.json
```

---

## Supported Languages

Java, Kotlin, Python, TypeScript, JavaScript, Go, Rust, C#, Swift, Dart, Ruby, PHP, Scala, Groovy, Vue, Svelte, Elixir

## Available Patterns

| Pattern | Description |
|---------|-------------|
| MVC | Model-View-Controller |
| SOLID Principles | SRP, ISP, DIP |
| Repository | Data access abstraction |
| Clean Architecture | Uncle Bob's dependency rule |
| Domain-Driven Design | Bounded contexts, aggregates |
| Hexagonal | Ports and adapters |
| CQRS | Command/query separation |
| Microservices | Service boundaries |
| Layered Architecture | N-tier layers |
| Observer/Event-Driven | Pub/sub decoupling |
| Factory/Creational | Object creation patterns |
| Code Quality | Complexity, smells, coupling |
