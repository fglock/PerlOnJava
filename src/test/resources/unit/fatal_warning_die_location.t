use strict;
use warnings;
use Test::More;

my $error;
{
    use warnings FATAL => 'uninitialized';
    eval { print undef; };
    $error = $@;
}
like $error,
    qr/^Use of uninitialized value in print at .* line \d+\./,
    'fatal runtime warning includes the source location of the operation';

done_testing();
