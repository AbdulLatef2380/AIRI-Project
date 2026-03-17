import subprocess
import json
import os

REPORT_DIR = "analysis-report"
os.makedirs(REPORT_DIR, exist_ok=True)

commands = {
    "gradle_check": "./gradlew check --continue",
    "lint": "./gradlew lint --continue",
    "build": "./gradlew build --continue"
}

results = {}

for name, cmd in commands.items():
    print(f"\nRunning {name}...\n")

    process = subprocess.Popen(
        cmd,
        shell=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True
    )

    output = []
    for line in process.stdout:
        print(line.strip())
        output.append(line)

    process.wait()

    results[name] = {
        "return_code": process.returncode,
        "output": output
    }

report_file = os.path.join(REPORT_DIR, "full_report.json")

with open(report_file, "w") as f:
    json.dump(results, f, indent=2)

print("\nAnalysis finished.")
print(f"Report saved: {report_file}")
