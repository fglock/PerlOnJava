use strict;
use warnings;
use Test::More tests => 6;
{
    package CountingTie;
    our ($fetches, $stores) = (0, 0);
    sub TIESCALAR { bless [], shift }
    sub FETCH { ++$fetches; 17 }
    sub STORE { ++$stores }
}
tie my $value, 'CountingTie';
sub implicit_return { $value }
sub explicit_return { return $value }
sub assigned_return { $value = 3 }
implicit_return();
explicit_return();
assigned_return();
is($CountingTie::fetches, 0, 'void return paths do not materialize tied values');
is($CountingTie::stores, 1, 'void assignment still invokes STORE');
my $scalar = implicit_return();
is($scalar, 17, 'scalar return still fetches the tied value');
is($CountingTie::fetches, 1, 'scalar return fetches once');
my @list = explicit_return();
is_deeply(\@list, [17], 'list return still fetches the tied value');
is($CountingTie::fetches, 2, 'list return fetches once');
