import subprocess
import json
import re
import os
from datetime import datetime

PROJECT_ROOT = "."
REPORT_DIR = "analysis-report"
os.makedirs(REPORT_DIR, exist_ok=True)

GRADLE_CMD = "./gradlew build --stacktrace --info --warning-mode all --continue"

# ---------- RUN GRADLE ----------
def run_gradle():
    process = subprocess.Popen(
        GRADLE_CMD,
        shell=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True
    )

    lines = []
    for line in process.stdout:
        print(line.strip())
        lines.append(line)

    process.wait()
    return lines

# ---------- ERROR EXTRACTION ----------
FILE_PATTERN = r"(/.*\.(kt|java)):(\d+)"
ERROR_PATTERN = r"(Unresolved reference: .+|error: .+|Exception: .+)"

def extract_errors(lines):
    errors = []
    current = None

    for line in lines:
        line = line.strip()

        file_match = re.search(FILE_PATTERN, line)
        if file_match:
            current = {
                "file": file_match.group(1),
                "line": int(file_match.group(3)),
                "message": ""
            }
            errors.append(current)

        msg_match = re.search(ERROR_PATTERN, line)
        if msg_match and current:
            current["message"] = msg_match.group(1)

    return errors

# ---------- AUTO FIX ENGINE ----------

def fix_imports(file_path, symbol):
    """يحاول إضافة import تلقائي"""
    try:
        with open(file_path, "r") as f:
            content = f.readlines()

        # لا تضيف إذا موجود
        for line in content:
            if symbol in line and "import" in line:
                return False

        # insert بعد package
        for i, line in enumerate(content):
            if line.startswith("package"):
                content.insert(i + 1, f"import {symbol}\n")
                break

        with open(file_path, "w") as f:
            f.writelines(content)

        return True
    except:
        return False


def fix_unresolved_reference(error):
    """حل Unresolved reference"""
    msg = error["message"]
    file_path = "." + error["file"]

    match = re.search(r"Unresolved reference: (\w+)", msg)
    if not match:
        return False

    symbol = match.group(1)

    # تخمين imports شائعة
    COMMON_IMPORTS = [
        f"kotlin.{symbol}",
        f"java.util.{symbol}",
        f"android.{symbol}",
        f"androidx.{symbol}",
    ]

    for imp in COMMON_IMPORTS:
        if fix_imports(file_path, imp):
            print(f"✅ Fixed import: {imp}")
            return True

    return False


def remove_duplicate_imports(file_path):
    try:
        with open(file_path, "r") as f:
            lines = f.readlines()

        seen = set()
        new_lines = []

        for line in lines:
            if line.startswith("import"):
                if line in seen:
                    continue
                seen.add(line)
            new_lines.append(line)

        with open(file_path, "w") as f:
            f.writelines(new_lines)

        return True
    except:
        return False


def fix_file(error):
    file_path = "." + error["file"]

    if not os.path.exists(file_path):
        return False

    fixed = False

    # 1. unresolved reference
    if "Unresolved reference" in error["message"]:
        if fix_unresolved_reference(error):
            fixed = True

    # 2. تنظيف imports
    if remove_duplicate_imports(file_path):
        fixed = True

    return fixed


# ---------- MAIN ----------
def main():
    print("🚀 Running Build Analysis...\n")

    lines = run_gradle()
    errors = extract_errors(lines)

    print(f"\n🔥 Found {len(errors)} errors\n")

    fixed_count = 0

    for err in errors:
        if fix_file(err):
            fixed_count += 1

    print(f"\n🛠 Fixed {fixed_count} issues automatically")

    report = {
        "timestamp": datetime.now().isoformat(),
        "total_errors": len(errors),
        "fixed": fixed_count,
        "errors": errors
    }

    with open(os.path.join(REPORT_DIR, "autofix_report.json"), "w") as f:
        json.dump(report, f, indent=2)

    print("\n🔁 Re-running build after fixes...\n")

    run_gradle()


if __name__ == "__main__":
    main()
