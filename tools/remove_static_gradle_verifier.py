from pathlib import Path

path = Path('/home/ubuntu/AIRI-Project/app/build.gradle.kts')
text = path.read_text(encoding='utf-8')
start = text.index('tasks.register("airiVerifyOptimization")')
end = text.index('// ─────────────────────────────────────────────────────────────────────────\n// airiVerifyNativeInApk', start)
replacement = '''// Performance verification belongs in device benchmarks and CI test reports.\n// Do not synthesize performance success from fixed values in the build script.\n\n'''
path.write_text(text[:start] + replacement + text[end:], encoding='utf-8')
print('Removed static airiVerifyOptimization task.')
