use strict;
use warnings;
use Test::More tests => 4;

our $destroyed = 0;
our $reference_callbacks = 0;
{
    package NestedArrayRefReturnOwner;
    sub DESTROY { ++$main::destroyed }
}

sub build_results {
    my @callbacks = map {
        my $as_reference = $_;
        sub {
            my @items = (bless { branch => $as_reference }, 'NestedArrayRefReturnOwner');
            ++$main::reference_callbacks if $as_reference;
            return $as_reference ? \@items : @items;
        }
    } (0, 1);

    my @middle = map {
        my $callback = $_;
        map { $callback->() } (1, 2);
    } @callbacks;

    return map { $_ } @middle;
}

my @results = build_results();
is(scalar @results, 4, 'nested callbacks return all values');
is($destroyed, 0, 'returned values retain their objects');
is($reference_callbacks, 2, 'the aggregate-reference callback runs twice');
undef @results;
is($destroyed, 4, 'discarding the nested callback results releases every object');
