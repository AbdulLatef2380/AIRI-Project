import os
import re
import json
import networkx as nx
from rich.console import Console
from rich.table import Table
from rich.panel import Panel

console = Console()

class AIRIOracle:
    def __init__(self, root_dir):
        self.root_dir = root_dir
        self.graph = nx.DiGraph()
        self.files = []
        self.view_models = []
        self.repositories = []
        self.state_flows = []
        self.isolated_files = []
        self.fake_buttons = []
        self.jni_methods = []

    def scan_project(self):
        console.print("[bold blue]Scanning project files...[/bold blue]")
        for root, _, files in os.walk(self.root_dir):
            if "build" in root or ".git" in root:
                continue
            for file in files:
                if file.endswith(".kt") or file.endswith(".cpp") or file.endswith(".h"):
                    path = os.path.join(root, file)
                    self.files.append(path)
                    self.graph.add_node(path)

    def analyze_kotlin_file(self, path):
        with open(path, 'r', encoding='utf-8') as f:
            content = f.read()
            
            # Extract Package and Class Name
            pkg_match = re.search(r'package\s+([\w\.]+)', content)
            package = pkg_match.group(1) if pkg_match else ""
            class_match = re.search(r'class\s+(\w+)', content)
            class_name = class_match.group(1) if class_match else os.path.basename(path).replace(".kt", "")

            # Detect ViewModels and Repositories
            if "ViewModel" in class_name:
                self.view_models.append(class_name)
            if "Repository" in class_name:
                self.repositories.append(class_name)

            # Detect StateFlows
            flows = re.findall(r'MutableStateFlow\s*<.*>\s*\(', content)
            if flows:
                self.state_flows.append((class_name, len(flows)))

            # Detect Fake Buttons (onClick = {})
            if re.search(r'onClick\s*=\s*\{\s*\}', content) or "TODO()" in content or "NotImplementedError" in content:
                self.fake_buttons.append(path)

            # Detect JNI (external fun)
            # Example: external fun nativeInit(path: String): Long
            jni = re.findall(r'external\s+fun\s+(\w+)\((.*?)\)(?:\s*:\s*(\w+))?', content)
            for method, params, ret_type in jni:
                jni_name = f"Java_{package.replace('.', '_')}_{class_name}_{method}"
                self.jni_methods.append({
                    "name": method,
                    "jni_name": jni_name,
                    "file": path,
                    "type": "kotlin",
                    "params": params,
                    "return": ret_type or "Unit"
                })

            # Analyze Imports for Graph
            imports = re.findall(r'import\s+([\w\.]+)', content)
            for imp in imports:
                for other_file in self.files:
                    other_base = os.path.basename(other_file).replace(".kt", "")
                    if other_base in imp:
                        self.graph.add_edge(path, other_file)

    def analyze_cpp_file(self, path):
        with open(path, 'r', encoding='utf-8') as f:
            content = f.read()
            # Detect JNI implementations
            jni_impls = re.findall(r'JNIEXPORT\s+.*?\s+JNICALL\s+(Java_[\w_]+)', content)
            for impl in jni_impls:
                self.jni_methods.append({"name": impl, "file": path, "type": "cpp"})

    def run_analysis(self):
        for path in self.files:
            if path.endswith(".kt"):
                self.analyze_kotlin_file(path)
            elif path.endswith(".cpp") or path.endswith(".h"):
                self.analyze_cpp_file(path)

        # Detect Isolated Files
        for node in self.graph.nodes():
            if self.graph.in_degree(node) == 0 and self.graph.out_degree(node) == 0:
                if "MainActivity" not in node: # Exclude entry point
                    self.isolated_files.append(node)

    def report(self):
        console.print(Panel("[bold green]AIRI Oracle - Architectural Intelligence Report[/bold green]"))

        # Architecture Stats
        table = Table(title="Architecture Statistics")
        table.add_column("Component", style="cyan")
        table.add_column("Count", style="magenta")
        table.add_row("ViewModels", str(len(self.view_models)))
        table.add_row("Repositories", str(len(self.repositories)))
        table.add_row("Active StateFlows", str(len(self.state_flows)))
        console.print(table)

        # Isolated Files
        if self.isolated_files:
            console.print("\n[bold red]Disconnected Architecture (Isolated Files):[/bold red]")
            for f in self.isolated_files:
                console.print(f" ❌ {os.path.relpath(f, self.root_dir)}")

        # Fake Buttons
        if self.fake_buttons:
            console.print("\n[bold yellow]UI Integrity (Fake Buttons / Unfinished Logic):[/bold yellow]")
            for f in set(self.fake_buttons):
                console.print(f" ⚠️  {os.path.relpath(f, self.root_dir)}")

        # JNI Check
        kt_jni = [m for m in self.jni_methods if m['type'] == 'kotlin']
        cpp_jni = [m['name'] for m in self.jni_methods if m['type'] == 'cpp']
        
        console.print("\n[bold blue]Native Intelligence (JNI Bindings):[/bold blue]")
        for m in kt_jni:
            # Strict matching: check if the generated JNI name exists in C++
            match = m['jni_name'] in cpp_jni
            status = "[green]VALID[/green]" if match else "[red]BROKEN / MISSING[/red]"
            console.print(f" 🔗 {m['name']} -> {m['jni_name']}: {status}")
            if not match:
                console.print(f"    [dim]Expected in C++: {m['jni_name']}[/dim]")

if __name__ == "__main__":
    oracle = AIRIOracle(os.getcwd())
    oracle.scan_project()
    oracle.run_analysis()
    oracle.report()
    
    # Export to JSON for CI integration
    results = {
        "view_models": oracle.view_models,
        "repositories": oracle.repositories,
        "isolated_files": [os.path.relpath(f, oracle.root_dir) for f in oracle.isolated_files],
        "fake_buttons": [os.path.relpath(f, oracle.root_dir) for f in set(oracle.fake_buttons)],
        "jni_bindings": [
            {
                "kotlin_method": m['name'],
                "jni_name": m['jni_name'],
                "valid": m['jni_name'] in [x['name'] for x in oracle.jni_methods if x['type'] == 'cpp']
            }
            for m in oracle.jni_methods if m['type'] == 'kotlin'
        ]
    }
    with open("oracle_report.json", "w") as f:
        json.dump(results, f, indent=4)
