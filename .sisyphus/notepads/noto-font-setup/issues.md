# Issues Encountered

1. **GitHub raw download timeout**: Both Invoke-WebRequest and curl timed out when downloading font files (even 8MB OTF, timeout set to 300s).
   - Root cause: Slow network in the environment
   - Resolution: Documented manual download steps in README.md, created font-loading test with system font fallback

2. **Partial download**: First download attempt created a truncated 12KB TTC file before timeout. Manually cleaned up.
   - Resolution: Deleted partial file; only .gitkeep and README remain in fonts/ directory
