use strict;
use warnings;
use Test::More;

my $ascending_version_ok = eval q{
    use 5.006;
    use v5.10.0;
    1;
};
ok($ascending_version_ok, 'ascending minimum use VERSION declarations are allowed');

done_testing;
