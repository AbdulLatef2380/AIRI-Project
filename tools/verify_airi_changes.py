from pathlib import Path
import re
import xml.etree.ElementTree as ET

current_root = Path(__file__).resolve().parents[1]
roots = [current_root]
main_root = current_root.parent / 'AIRI-main'
if main_root.exists() and main_root != current_root:
    roots.append(main_root)
for root in roots:
    for p in (root/'app/src/main/res').glob('values*/strings.xml'):
        ET.parse(p)
    for p in [
        root/'app/src/main/java/com/airi/assistant/execution/ExecutionRequest.kt',
        root/'app/src/main/java/com/airi/assistant/execution/cloud/GeminiAdapter.kt',
        root/'app/src/main/java/com/airi/assistant/execution/cloud/OpenAIAdapter.kt',
        root/'app/src/main/java/com/airi/assistant/agent/loop/AgentLoop.kt',
        root/'app/src/main/java/com/airi/assistant/ui/viewmodel/ChatViewModel.kt',
    ]:
        assert p.exists(), p
        text = p.read_text()
        assert text.strip(), p
    req = (root/'app/src/main/java/com/airi/assistant/execution/ExecutionRequest.kt').read_text()
    gem = (root/'app/src/main/java/com/airi/assistant/execution/cloud/GeminiAdapter.kt').read_text()
    oai = (root/'app/src/main/java/com/airi/assistant/execution/cloud/OpenAIAdapter.kt').read_text()
    loop = (root/'app/src/main/java/com/airi/assistant/agent/loop/AgentLoop.kt').read_text()
    vm = (root/'app/src/main/java/com/airi/assistant/ui/viewmodel/ChatViewModel.kt').read_text()
    for marker, text in [('imageParts', req), ('inline_data', gem), ('image_url', oai), ('visionParts', loop), ('visionImagePart', vm)]:
        assert marker in text, (root, marker)
print('AIRI static verification passed for main and cp-foundation')
