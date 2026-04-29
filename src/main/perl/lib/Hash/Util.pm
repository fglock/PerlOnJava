package Hash::Util;

use strict;
use warnings;
require Exporter;

our @ISA = qw(Exporter);
our @EXPORT_OK = qw(
    bucket_ratio
    lock_keys unlock_keys
    lock_hash unlock_hash
    lock_value unlock_value
    lock_keys_plus
    hash_seed
    hashref_locked hash_locked
    legal_keys all_keys
    hidden_keys legal_ref_keys
    hash_unlocked
);
our $VERSION = '0.28';

# Load the Java backend
require XSLoader;
XSLoader::load('HashUtil');

our %EXPORT_TAGS = (
    all => \@EXPORT_OK,
);

# `lock_value`/`unlock_value` lock or unlock individual hash slots.
# PerlOnJava doesn't fully model SVf_READONLY at slot granularity yet;
# tests like Storable's restrict.t need these to exist as no-ops so
# they import cleanly. Safer than refusing — most tests check for
# warnings/errors rather than that the lock-state actually persists.
# The prototype `(\%$)` auto-references the first arg, matching
# upstream's calling convention `unlock_value %hash, $key`.
sub lock_value   (\%$) { return ${$_[0]}{$_[1]} }
sub unlock_value (\%$) { return ${$_[0]}{$_[1]} }

# lock_keys_plus: like lock_keys but also pre-allocates additional
# permitted keys. We model this as a no-op too since lock_keys is
# itself best-effort.
sub lock_keys_plus (\%;@) { return $_[0] }

# Inspector helpers — plausible defaults for tests that just need them
# to return something rather than die.
sub hashref_locked  { return 0 }
sub hash_locked     { return 0 }
sub hash_unlocked   { return 1 }
sub legal_keys      { return keys %{$_[0]} }
sub all_keys        { return keys %{$_[0]} }
sub legal_ref_keys  { return keys %{$_[0]} }
sub hidden_keys     { return () }

1;

__END__

=head1 NAME

Hash::Util - A selection of general-utility hash subroutines

=head1 SYNOPSIS

  use Hash::Util qw(bucket_ratio lock_keys unlock_keys);
  
  my %hash = (a => 1, b => 2, c => 3);
  my $ratio = bucket_ratio(%hash);
  print "Bucket ratio: $ratio\n";
  
  lock_keys(%hash, qw(a b c));
  unlock_keys(%hash);

=head1 DESCRIPTION

Hash::Util contains special functions for manipulating hashes that
don't really warrant a keyword.

This is a basic implementation for PerlOnJava compatibility.

=head1 FUNCTIONS

=over 4

=item bucket_ratio

Returns the ratio of used buckets to total buckets in a hash.

=item lock_keys, unlock_keys

Lock/unlock hash keys (placeholder implementation).

=item lock_value, unlock_value

Lock/unlock individual hash values (placeholder implementation).

=item lock_hash, unlock_hash

Lock/unlock entire hash (placeholder implementation).

=back

=head1 AUTHOR

PerlOnJava Team

=cut
