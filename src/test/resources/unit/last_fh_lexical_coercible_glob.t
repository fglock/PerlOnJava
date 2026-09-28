use strict;
use warnings;
use Test::More;

{
    () = tell STDOUT;
    is(${^LAST_FH}, \*STDOUT,
        'LAST_FH returns a glob reference for a direct named glob');

    my $fh = *STDOUT;
    () = tell $fh;
    is(${^LAST_FH}, \$fh,
        'LAST_FH retains the lexical scalar that supplied a coercible glob');
}

is(${^LAST_FH}, undef,
    'LAST_FH no longer exposes a lexical coercible glob after its scope exits');

done_testing;
