# PerlOnJava CPAN patches

Keep patch sources under `Distribution-Version/` so the release used to
author a patch remains visible. Distroprefs must reference the stable,
version-independent path `Distribution/PatchName.patch`; `CPAN::Config` maps
that installed path to the versioned source during bootstrap.

This allows a broad distribution match to try the patch after a new CPAN
release. CPAN's normal patch context and fuzz checks still decide whether the
upstream source is compatible; refresh the versioned source patch when those
checks fail.
