use strict;
use warnings;
use Test::More tests => 1;

my $compiled = eval q{ qr/(?(1)x/; 1 };
like($@, qr/^Switch \(\?\(condition\)\.\.\. not terminated in regex; marked by <-- HERE in m\/\(\?\(1\)x <-- HERE \/ at /,
     'unterminated conditional uses the Perl diagnostic');
