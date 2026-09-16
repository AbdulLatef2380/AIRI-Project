from pathlib import Path
import re
import xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[1]
keys=set()
for p in (root/'app/src/main/res').glob('values*/strings.xml'):
    tree=ET.parse(p)
    keys |= {e.attrib['name'] for e in tree.getroot() if e.tag=='string'}
missing=[]
for p in (root/'app/src/main/java').rglob('*.kt'):
    for k in re.findall(r'R\.string\.([A-Za-z0-9_]+)', p.read_text()):
        if k not in keys: missing.append((str(p),k))
if missing:
    raise SystemExit('\n'.join(f'{p}: {k}' for p,k in sorted(set(missing))))
print(f'resource references ok: {len(keys)} keys')
