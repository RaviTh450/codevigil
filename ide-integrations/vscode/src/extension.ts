import * as path from 'path';
import * as vscode from 'vscode';
import {
    LanguageClient,
    LanguageClientOptions,
    ServerOptions,
    TransportKind,
} from 'vscode-languageclient/node';

let client: LanguageClient;

export function activate(context: vscode.ExtensionContext) {
    const config = vscode.workspace.getConfiguration('codevigil');
    const javaPath = config.get<string>('javaPath', 'java');

    // Path to the CLI JAR — bundled with the extension
    const jarPath = context.asAbsolutePath(path.join('server', 'codevigil-cli.jar'));

    const serverOptions: ServerOptions = {
        command: javaPath,
        args: ['-jar', jarPath, '--lsp'],
        transport: TransportKind.stdio,
    };

    const clientOptions: LanguageClientOptions = {
        documentSelector: [
            { scheme: 'file', language: 'java' },
            { scheme: 'file', language: 'kotlin' },
            { scheme: 'file', language: 'python' },
            { scheme: 'file', language: 'typescript' },
            { scheme: 'file', language: 'javascript' },
            { scheme: 'file', language: 'go' },
            { scheme: 'file', language: 'rust' },
            { scheme: 'file', language: 'csharp' },
            { scheme: 'file', language: 'swift' },
            { scheme: 'file', language: 'dart' },
            { scheme: 'file', language: 'ruby' },
            { scheme: 'file', language: 'php' },
        ],
    };

    client = new LanguageClient(
        'codevigil',
        'CodeVigil',
        serverOptions,
        clientOptions
    );

    // Register commands
    context.subscriptions.push(
        vscode.commands.registerCommand('codevigil.scanProject', async () => {
            const result = await client.sendRequest('codevigil/scan');
            const data = result as any;
            vscode.window.showInformationMessage(
                `Scan complete: ${data.totalViolations} violations in ${data.totalFiles} files. Health: ${data.healthScore}/100`
            );
        }),

        vscode.commands.registerCommand('codevigil.selectPattern', async () => {
            const listResult = await client.sendRequest('codevigil/listPatterns');
            const patterns = (listResult as any).patterns as string[];
            const selected = await vscode.window.showQuickPick(patterns, {
                placeHolder: 'Select architectural pattern',
            });
            if (selected) {
                await client.sendRequest('codevigil/setPattern', { patternName: selected });
                vscode.window.showInformationMessage(`Pattern set to: ${selected}`);
            }
        }),

        vscode.commands.registerCommand('codevigil.exportReport', async () => {
            const uri = await vscode.window.showSaveDialog({
                filters: { 'HTML': ['html'], 'JSON': ['json'] },
            });
            if (uri) {
                vscode.window.showInformationMessage('Use the CLI tool for report export: java -jar codevigil-cli.jar --format html --output report.html');
            }
        })
    );

    client.start();
}

export function deactivate(): Thenable<void> | undefined {
    if (!client) return undefined;
    return client.stop();
}
