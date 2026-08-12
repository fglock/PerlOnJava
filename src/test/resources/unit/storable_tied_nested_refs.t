use strict;
use warnings;
use Test::More tests => 7;
use Storable qw(freeze thaw);
use Tie::Hash;
use Scalar::Util qw(blessed reftype);

{
    package Local::OrderedTie;
    our @ISA = qw(Tie::StdHash);
    sub TIEHASH { bless {}, shift }
}

tie my %inner, 'Local::OrderedTie';
$inner{answer} = 42;

my $copy = thaw(freeze({
    direct => \%inner,
    array  => [\%inner],
    hash   => { inner => \%inner },
}));

is(ref($copy->{direct}), 'HASH', 'nested tied hash retains one reference level');
is(ref($copy->{array}[0]), 'HASH', 'tied hash in array retains one reference level');
is(ref($copy->{hash}{inner}), 'HASH', 'tied hash in hash retains one reference level');
is($copy->{direct}{answer}, 42, 'direct tied value survives');
is($copy->{array}[0]{answer}, 42, 'array tied value survives');
is($copy->{hash}{inner}{answer}, 42, 'hash tied value survives');
is(blessed(tied(%{$copy->{direct}})), 'Local::OrderedTie', 'tying object remains blessed');
