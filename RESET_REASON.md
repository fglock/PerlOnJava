# Reset master to working commit 6d7f5563

This commit resets master to the last known working state to fix critical regressions.

## Problems in current master (d6400c01):
- ASM frame computation errors in Getopt::Long processing  
- Command line parsing failures in life_bitpacked.pl example

## This working commit (6d7f5563) fixes:
- ✅ ./jperl examples/life_bitpacked.pl --width=10 --height=5 --generations=1 works
- ✅ All tests pass
- ✅ No ASM frame errors  
- ✅ Getopt::Long functions correctly

## Reverted problematic commits:
- b48654f7, c08ebfdc - Broke Getopt::Long with constant subroutine prototype changes
- d6400c01 - Current HEAD with ASM frame computation errors

This restores master to a stable state while the regressions can be properly investigated and fixed.
