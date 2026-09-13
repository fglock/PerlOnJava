use strict;
use warnings;
use Test::More;

my $compiled = eval q!sub example { } }!;
ok !defined $compiled, 'unmatched right curly does not compile';
like $@, qr/^Unmatched right curly bracket at /,
    'eval reports an unmatched right curly diagnostic';

done_testing;
