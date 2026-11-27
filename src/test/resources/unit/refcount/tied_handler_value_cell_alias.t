use strict;
use warnings;
use Test::More;

sub TIEARRAY { bless [], shift }
my @array;
${\tie @array, 'main'} = undef;
my $array_error = eval { my $value = $array[-1]; 1 } ? '' : $@;
like(
    $array_error,
    qr/Can't call method "FETCHSIZE" on an undefined value/,
    'tied array dispatch observes mutation of the returned handler cell',
);

sub TIESCALAR : lvalue { ${+pop} = bless [], shift }
my ($tied_scalar, $scalar, $assigned);
tie $tied_scalar, 'main', \$scalar;
$scalar = 7;
($scalar, $assigned) = (3, tied $tied_scalar);
is($scalar, 3, 'list assignment updates the aliased scalar');
is($assigned, 7, 'tied returns the value from its lvalue handler cell');

done_testing;
