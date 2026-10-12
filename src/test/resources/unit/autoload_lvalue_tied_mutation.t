use strict;
use warnings;
use Test::More tests => 2;

package Local::Issue1207;
our $AUTOLOAD;

sub new {
    my $class = shift;
    tie my %data, 'Local::Issue1207::Tie', { apples => 1 };
    return bless \%data, $class;
}

sub AUTOLOAD : lvalue {
    my ($key) = $AUTOLOAD =~ /([^:]+)\z/;
    return ref($_[0]) ? $_[0]->{$key} : $_[1]->{$key};
}

package Local::Issue1207::Tie;
sub TIEHASH { bless { data => $_[1] }, $_[0] }
sub FETCH { $_[0]{data}{$_[1]} }
sub STORE { $_[0]{data}{$_[1]} = $_[2] }

package main;
my $hash = Local::Issue1207->new;
$hash->apples++;
is($hash->{apples}, 2,
    'post-increment mutates a tied hash through an lvalue AUTOLOAD accessor');

Local::Issue1207->apples($hash) *= 7;
is($hash->{apples}, 14,
    'compound assignment mutates a tied hash through a class lvalue AUTOLOAD accessor');
