use strict;
use warnings;
use Test::More tests => 2;

$SIG{__WARN__} = sub { die @_ };
eval { warn "warning from handler\n" };
like($@, qr/^warning from handler\n/,
    'a warning handler rethrows its warning text through die @_');

eval { warn "second warning from handler\n" };
like($@, qr/^second warning from handler\n/,
    'a dying warning handler is restored for the next warning');
