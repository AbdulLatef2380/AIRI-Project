from pathlib import Path
p=Path('/home/ubuntu/AIRI-main/app/src/main/java/com/airi/assistant/ui/screens/AppInfoScreen.kt')
s=p.read_text()
start=s.index('            AboutCard(icon = Icons.Outlined.Shield')
end=s.index('            AboutCard(icon = Icons.Outlined.Code', start)
replacement='''            AboutCard(icon = Icons.Outlined.Shield, title = stringResource(R.string.about_privacy_title)) {\n                Text(stringResource(R.string.about_privacy_full), color = AiriTheme.onBackground.copy(alpha = 0.72f), fontSize = 13.sp, lineHeight = 20.sp)\n            }\n            AboutCard(icon = Icons.Outlined.Gavel, title = stringResource(R.string.about_terms)) {\n                Text(stringResource(R.string.about_terms_full), color = AiriTheme.onBackground.copy(alpha = 0.72f), fontSize = 13.sp, lineHeight = 20.sp)\n            }\n'''
s=s[:start]+replacement+s[end:]
s=s.replace('''                    "AIRI is built on the shoulders of open-source giants:",''','''                    stringResource(R.string.about_acknowledgements_intro),''')
p.write_text(s)
