use strict;
use warnings;
use Test::More tests => 6;

our $destroyed = 0;

{
    package HashListOuterReturn;
    sub DESTROY { ++$main::destroyed }
}

sub produce {
    return bless {}, 'HashListOuterReturn';
}

sub materialize_hash {
    my %hash = (first => 1, second => 2);
    return scalar keys %hash;
}

my @list = (produce(), materialize_hash());
is($destroyed, 0,
    'list expression keeps earlier return alive during nested hash assignment');
is(ref($list[0]), 'HashListOuterReturn',
    'earlier return is still in the assigned list');
undef @list;
is($destroyed, 1, 'list owner releases the return once it is cleared');

$destroyed = 0;
my $array = [produce(), materialize_hash()];
is($destroyed, 0,
    'array expression keeps earlier return alive during nested hash assignment');
is(ref($array->[0]), 'HashListOuterReturn',
    'earlier return is still in the assigned array');
undef $array;
is($destroyed, 1, 'array owner releases the return once it is cleared');
