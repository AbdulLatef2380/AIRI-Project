# Live API and Local Runtime Verification

## Cloud API smoke test

`tools/stability/cloud_api_smoke.py` sends one minimal text request to each selected provider using the same HTTP contracts as AIRI's Android adapters. OpenAI uses streaming Chat Completions with `Authorization: Bearer`; Gemini uses `streamGenerateContent?alt=sse` with `x-goog-api-key`, matching the application implementation. The script requires keys from environment variables and never prints them.

```bash
# Test both; both keys are required in this mode.
GEMINI_API_KEY='...' OPENAI_API_KEY='...' \
  python3 tools/stability/cloud_api_smoke.py

# Test one provider.
OPENAI_API_KEY='...' \
  python3 tools/stability/cloud_api_smoke.py --provider openai

# CI mode: test whichever provider has a configured secret.
python3 tools/stability/cloud_api_smoke.py --allow-missing
```

A successful provider reports `status: PASS`, HTTP status, latency, received chunk count, and response length. The response body is not printed. Exit code `1` means a configured provider failed, `2` means a required key is missing, and `0` means all requested checks passed. The default models are `gpt-4o-mini` and the model configured by AIRI's Gemini adapter, `gemini-3.8-flash`; both can be overridden with command-line flags or environment variables.

The Android CI workflow runs this smoke test only when at least one of the optional GitHub Actions secrets `OPENAI_API_KEY` or `GEMINI_API_KEY` exists. Missing providers are reported as skipped, while a configured provider returning an authentication, quota, protocol, or network error fails the job. This prevents CI from silently claiming cloud coverage without credentials.

## Local performance simulation

`tools/stability/local_model_performance_sim.py` is dependency-free and safe to run in CI. It validates context admission, bounded output, terminal stream completion, cancellation guards, and repeated cancellation cycles while measuring the host-side synthetic stream latency and throughput.

```bash
python3 tools/stability/local_model_performance_sim.py
python3 tools/stability/local_model_performance_sim.py --runs 100 --output-tokens 256 --cancel-cycles 500
python3 tools/stability/local_model_performance_sim.py --gguf /path/to/model.gguf
```

The JSON output deliberately labels itself `contract_simulation_not_native_inference`. It is evidence that the local orchestration contracts are stable and that cancellation/admission logic does not regress; it is **not** a substitute for measuring llama.cpp tokens/second on a physical Android device. If a real GGUF path is supplied, the script additionally checks its GGUF magic, supported version, and minimum size before running the simulation.

## CI monitoring

The repository's Android workflow can be inspected with:

```bash
gh run list --repo AbdulLatef2380/AIRI-Project --workflow android_build.yml --limit 10
gh run watch RUN_ID --repo AbdulLatef2380/AIRI-Project --exit-status
```

CI also continues to run the existing Android compile, unit tests, lint, native build, and emulator instrumentation suite. A green run proves the repository-level checks; a live provider smoke result additionally proves that the configured key, endpoint, model ID, authentication, and response protocol are working at that moment.
