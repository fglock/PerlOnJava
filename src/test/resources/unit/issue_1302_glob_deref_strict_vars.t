use strict;
use warnings;
use Test::More;

# Perl parses `@$` as the array slot of the special glob named `$` (gv[*$]).
# Keep this expression directly in the compilation unit: compiling it in an
# eval string does not reproduce the JVM backend's strict-vars failure.
my @glob_array = @$;
is_deeply(\@glob_array, [], '`@$` dereferences the special glob array under strict vars');

done_testing;
