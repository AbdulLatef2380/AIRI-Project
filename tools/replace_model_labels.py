from pathlib import Path
p = Path('/home/ubuntu/AIRI-main/app/src/main/java/com/airi/assistant/ui/screens/ModelSettingsScreen.kt')
s = p.read_text()
for old, new in {
    'label = "RAM"': 'label = stringResource(R.string.models_ram)',
    'label = "Context"': 'label = stringResource(R.string.models_context)',
    'label = "Size"': 'label = stringResource(R.string.models_size)',
    'label = "Quant"': 'label = stringResource(R.string.models_quant)',
}.items():
    s = s.replace(old, new)
p.write_text(s)
