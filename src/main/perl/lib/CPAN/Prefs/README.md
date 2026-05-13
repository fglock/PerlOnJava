# CPAN `Prefs/` (deprecated)

Bundled CPAN **distroprefs** for PerlOnJava are maintained under:

`src/main/perl/lib/PerlOnJava/CpanDistroprefs/`

They are copied to `~/.perlonjava/cpan/prefs/` by `CPAN::Config::_bootstrap_prefs` when CPAN loads. See [dev/design/patch-and-cpan-prefs-layout.md](../../../../../../dev/design/patch-and-cpan-prefs-layout.md).

This directory intentionally contains **no** `.yml` files so contributors are not confused by a second copy of prefs.
