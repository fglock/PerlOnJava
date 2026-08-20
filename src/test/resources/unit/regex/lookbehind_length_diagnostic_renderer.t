use strict;
use warnings;
use Test::More tests => 2;

my $compiled = eval q{ qr/(?<= a{255})/; 1 };
ok(!$compiled, 'overlong lookbehind does not compile');
like($@, qr/^Lookbehind longer than 255 not implemented in regex m\/\(\?<= a\{255\}\)\/ at /,
     'overlong lookbehind uses the unmarked Perl diagnostic');
