use strict;
use warnings;
use Test::More;
our ($destroyed, @events);
$destroyed = 0;
{
    package ConditionalTiedResult;
    sub TIESCALAR { bless { value => 41 }, shift }
    sub FETCH { push @main::events, 'FETCH'; $_[0]{value} }
    sub STORE { push @main::events, 'STORE'; $_[0]{value} = $_[1] }
    sub DESTROY { push @main::events, 'DESTROY'; ++$main::destroyed }
}
sub scalar_result {
    if (tie my $value, 'ConditionalTiedResult') { $value }
}
is(scalar_result(), 41, 'conditional tied scalar returns its fetched value');
is_deeply(\@events, ['FETCH', 'DESTROY'], 'rvalue fetch precedes conditional tie destruction');
@events = ();
sub lvalue_result :lvalue {
    if (tie my $value, 'ConditionalTiedResult') { $value }
}
lvalue_result() = 73;
is_deeply(\@events, ['STORE', 'DESTROY'], 'lvalue store precedes conditional tie destruction');
is($destroyed, 2, 'both conditional tied handlers release exactly once');
{
    package ConditionalHashLvalueOwner;
    sub TIEHASH { bless {}, shift }
    sub FETCH { push @main::events, 'FETCH'; 0 }
    sub STORE { push @main::events, 'STORE' }
    sub DESTROY { push @main::events, 'DESTROY'; ++$main::destroyed }
    package ConditionalArrayLvalueOwner;
    sub TIEARRAY { bless {}, shift }
    sub FETCHSIZE { 1 }
    sub FETCH { push @main::events, 'FETCH'; 0 }
    sub STORE { push @main::events, 'STORE' }
    sub DESTROY { push @main::events, 'DESTROY'; ++$main::destroyed }
}
sub hash_lvalue_result :lvalue {
    if (tie my %value, 'ConditionalHashLvalueOwner') { $value{slot} }
}
sub array_lvalue_result :lvalue {
    if (tie my @value, 'ConditionalArrayLvalueOwner') { $value[0] }
}
@events = ();
hash_lvalue_result() = 83;
is_deeply(\@events, ['STORE', 'DESTROY'], 'returned hash proxy retains its magic through STORE');
@events = ();
array_lvalue_result() = 97;
is_deeply(\@events, ['STORE', 'DESTROY'], 'returned array proxy retains its magic through STORE');
is($destroyed, 4, 'all conditional tied owners retire once');
done_testing();
