from pathlib import Path

root = Path('/home/ubuntu/AIRI-Project/app/src')
old = 'AIRI_PROOF'
new = 'AIRI_RUNTIME'
changed = 0
replacements = 0

for path in root.rglob('*.kt'):
    text = path.read_text(encoding='utf-8')
    count = text.count(old)
    if count:
        path.write_text(text.replace(old, new), encoding='utf-8')
        changed += 1
        replacements += count

print(f'normalized_files={changed}')
print(f'normalized_markers={replacements}')
