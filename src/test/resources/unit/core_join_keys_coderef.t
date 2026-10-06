use strict;
use warnings;
use Test::More tests => 20;

my $join = \&CORE::join;
is($join->(',', 'a', 'b'), 'a,b',
    'CORE::join code reference joins its remaining arguments');
eval { $join->() };
like($@, qr/^Not enough arguments for join or string at .+ line \d+\.\n$/,
    'CORE::join code reference uses the Perl arity diagnostic');

my $keys = \&CORE::keys;
my $values = \&CORE::values;
my %hash = (a => 1, b => 2);
is_deeply([sort $keys->(\%hash)], [qw(a b)],
    'CORE::keys code reference returns hash keys');
$keys->(\%hash) = 17;
pass('CORE::keys code reference retains its lvalue behavior');
is(scalar $keys->(\%hash), 2,
    'CORE::keys code reference returns the hash size in scalar context');
is_deeply([sort $values->(\%hash)], [1, 2],
    'CORE::values code reference returns hash values');

my @array = qw(x y);
is_deeply([$keys->(\@array)], [0, 1],
    'CORE::keys code reference returns array indices');
is_deeply([$values->(\@array)], [qw(x y)],
    'CORE::values code reference returns array values');

my $pack = \&CORE::pack;
my $unpack = \&CORE::unpack;
my $packed = $pack->('C', 65);
is_deeply([$unpack->('C', $packed)], [65],
    'CORE::pack and CORE::unpack code references round-trip a byte');

my $sprintf = \&CORE::sprintf;
is($sprintf->('%s:%d', 'a', 2), 'a:2',
    'CORE::sprintf code reference formats its remaining arguments');

my $not = \&CORE::not;
is($not->(1), '', 'CORE::not code reference negates a true value');
is($not->(0), 1, 'CORE::not code reference negates a false value');

my $lock = \&CORE::lock;
my $scalar = 'value';
my @locked_array = (1, 2);
my %locked_hash = (a => 1);
my $code = sub { 1 };
is($lock->(\$scalar), $scalar, 'CORE::lock returns the scalar value');
is(\$lock->(\$scalar), \$scalar,
    'CORE::lock code reference preserves its scalar lvalue');
eval { $lock->(1) };
like($@, qr/^Type of arg 1 to &CORE::lock must be reference to one of/,
    'CORE::lock code reference validates its argument type');
my $io_handle = *STDOUT{IO};
eval { $lock->($io_handle) };
like($@, qr/^Type of arg 1 to &CORE::lock must be reference to one of/,
    'CORE::lock code reference rejects an I/O handle reference');
is($lock->(\@locked_array), \@locked_array,
    'CORE::lock returns an array reference');
is($lock->(\%locked_hash), \%locked_hash,
    'CORE::lock returns a hash reference');
is($lock->($code), $code, 'CORE::lock returns a code reference');
{
    no warnings 'once';
    *mylock = \&CORE::lock;
    is(\&mylock(\*foo), \*foo,
        'CORE::lock code reference preserves its glob lvalue');
}
