#!/usr/bin/env perl
# Tests for autovivification of $x via dereference operations that
# bind a fresh hash/array back into $x.
#
# These exercise patterns where Perl's autovivification must update
# the *original* scalar, not just produce a one-shot hash/array for
# the immediate operation.  The most common real-world hit is
# YAML.pm 1.31's yaml_mapping/yaml_sequence:
#
#   sub new {
#       my ($class, $self) = @_;
#       my $new;
#       tie %$new, $class, $self;   # $new must autoviv to a hashref
#       $new                        # ... and that ref is what we return
#   }
#
# Without the fix, $new stays undef after `tie %$new, ...`, so the
# returned value is undef and YAML::Dumper later dies trying to walk it.

use strict;
use warnings;
use Test::More;

# ---------------------------------------------------------------------
# Backing class for tie tests below.  Doesn't matter what it does;
# we only care that `tie` on it succeeds and binds $h/$a back.
# ---------------------------------------------------------------------
package T::Hash;
sub TIEHASH  { bless { wrapped => $_[1] }, $_[0] }
sub FETCH    { $_[0]{wrapped}{$_[1]} }
sub STORE    { $_[0]{wrapped}{$_[1]} = $_[2] }
sub FIRSTKEY { my $a = scalar keys %{$_[0]{wrapped}}; each %{$_[0]{wrapped}} }
sub NEXTKEY  { each %{$_[0]{wrapped}} }
sub EXISTS   { exists $_[0]{wrapped}{$_[1]} }
sub DELETE   { delete $_[0]{wrapped}{$_[1]} }
sub CLEAR    { %{$_[0]{wrapped}} = () }
sub SCALAR   { scalar %{$_[0]{wrapped}} }
sub UNTIE    { }

package T::Array;
sub TIEARRAY { bless { wrapped => $_[1] // [] }, $_[0] }
sub FETCH    { $_[0]{wrapped}[$_[1]] }
sub STORE    { $_[0]{wrapped}[$_[1]] = $_[2] }
sub FETCHSIZE{ scalar @{$_[0]{wrapped}} }
sub STORESIZE{ $#{$_[0]{wrapped}} = $_[1] - 1 }
sub UNTIE    { }

package main;

# ---------------------------------------------------------------------
# tie %$undef, ...  must autoviv $undef to a hashref
# (this is what YAML::Node::yaml_mapping::new relies on)
# ---------------------------------------------------------------------
{
    my $h;
    tie %$h, 'T::Hash', { a => 1, b => 2 };
    ok defined($h),         'tie %$undef, ... binds $undef to a hashref';
    is ref($h), 'HASH',     'tie %$undef, ... → ref($undef) eq HASH';
    is $h->{a}, 1,          'tied hash FETCH works through autovivified $undef';
}

# ---------------------------------------------------------------------
# tie @$undef, ...  must autoviv $undef to an arrayref
# (this is what YAML::Node::yaml_sequence::new relies on)
# ---------------------------------------------------------------------
{
    my $a;
    tie @$a, 'T::Array', [10, 20, 30];
    ok defined($a),         'tie @$undef, ... binds $undef to an arrayref';
    is ref($a), 'ARRAY',    'tie @$undef, ... → ref($undef) eq ARRAY';
    is $a->[1], 20,         'tied array FETCH works through autovivified $undef';
}

# ---------------------------------------------------------------------
# Smoke test: a YAML::Node-style helper that returns the autovivified
# variable, mirroring real YAML.pm's yaml_mapping::new / yaml_sequence::new.
# ---------------------------------------------------------------------
{
    sub make_tied_hash {
        my $new;
        tie %$new, 'T::Hash', { x => 'ok' };
        return $new;
    }
    my $h = make_tied_hash();
    ok defined($h),                             'helper returns a defined value';
    is ref($h), 'HASH',                         'helper returns a hashref';
    is $h->{x}, 'ok',                           'tied FETCH works on returned ref';
}

done_testing;
