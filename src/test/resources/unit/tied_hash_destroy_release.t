use strict;
use warnings;
use Test::More;

package THD::LDAPLike;

sub new {
    bless {}, shift;
}

sub outer {
    my $self = shift;
    my %outer;
    tie %outer, ref($self), $self;
    ++$self->{refcnt};
    bless \%outer, ref($self);
}

sub inner {
    tied(%{$_[0]}) || $_[0];
}

sub TIEHASH {
    $_[1];
}

sub DESTROY {
    my $self = shift;
    ++$main::destroyed unless tied(%$self);

    my $inner = tied(%$self) or return;
    --$inner->{refcnt};
}

package main;

{
    my $outer = THD::LDAPLike->new->outer;
    ok(tied(%$outer), 'outer object is backed by a tied hash');
}

is($main::destroyed, 1, 'destroying a tied outer hash releases the inner object');

done_testing;
