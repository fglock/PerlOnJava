use strict;
use warnings;
use Test::More;

my $ok = eval q{map { for our *a (1..10) { $_ .= $x } }};

ok(!$ok, 'a typeglob cannot be a foreach iterator');
like($@, qr{\AMissing \$ on loop variable at \(eval \d+\) line 1\.\n?\z},
     'typeglob iterator reports the Perl loop-variable diagnostic');

done_testing;
