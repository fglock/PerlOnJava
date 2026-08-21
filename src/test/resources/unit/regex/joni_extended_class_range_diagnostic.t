use strict;
use warnings;
use Test::More;

my $invalid_range = eval q{ qr/[\xdf-/i; 1 } ? '' : $@;
like($invalid_range, qr/^Invalid \[\] range/,
     'unterminated character-class range uses the Perl diagnostic');

done_testing;
