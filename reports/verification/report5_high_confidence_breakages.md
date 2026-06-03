# REPORT 5 — High-Confidence Broken References
*Generated: 2026-05-31 21:36:06*

## Summary

**0 high-confidence compile errors detected**

These are issues where the static analyzer has HIGH confidence that the Kotlin compiler will produce an error.


## High-Confidence Broken References

**No high-confidence compile errors detected.**

All deleted symbols appear to have been properly cleaned up.


## Analysis Methodology

A reference is marked HIGH confidence if:
1. The referenced symbol name matches a symbol confirmed deleted from the codebase AND
2. The reference appears in a non-comment line AND
3. The line is not itself a definition (i.e., not creating a new class with the same name)


