package Hash::Util;

use strict;
use warnings;
require Exporter;

our @ISA = qw(Exporter);
our @EXPORT_OK = qw(
    bucket_ratio
    lock_keys unlock_keys
    lock_hash unlock_hash
    hash_seed
);
our $VERSION = '0.28';

# Load the Java backend
require XSLoader;
XSLoader::load('HashUtil');

our %EXPORT_TAGS = (
    all => \@EXPORT_OK,
);

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
