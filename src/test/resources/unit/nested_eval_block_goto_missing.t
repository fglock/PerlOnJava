use strict;
use warnings;
use Test::More tests => 3;

my $result = eval q{
    eval { goto missing_label };
    [ $@, 42 ];
};
my $outer_error = $@;

like($result->[0], qr/Can't find label missing_label/,
    'eval BLOCK catches a missing goto label inside eval STRING');
is($result->[1], 42, 'eval STRING continues after the inner eval');
is($outer_error, '', 'the control-flow error does not escape to the outer eval');
