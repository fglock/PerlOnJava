package Clone::PP;

use 5.006;
use strict;
use warnings;
use vars qw($VERSION @EXPORT_OK);
use Exporter;

$VERSION = 1.08;

@EXPORT_OK = qw( clone );
sub import { goto &Exporter::import } # lazy Exporter

# These methods can be temporarily overridden to work with a given class.
use vars qw( $CloneSelfMethod $CloneInitMethod );
$CloneSelfMethod ||= 'clone_self';
$CloneInitMethod ||= 'clone_init';

# Used to detect looped networks and avoid infinite recursion. 
use vars qw( %CloneCache );

# Generic cloning function
sub clone {
  my $source = shift;

  return undef if not defined($source);
  
  # Optional depth limit: after a given number of levels, do shallow copy.
  my $depth = shift;
  return $source if ( defined $depth and $depth -- < 1 );
  
  # Maintain a shared cache during recursive calls, then clear it at the end.
  local %CloneCache = ( undef => undef ) unless ( exists $CloneCache{undef} );
  
  return $CloneCache{ $source } if ( defined $CloneCache{ $source } );
  
  # Non-reference values are copied shallowly
  my $ref_type = ref $source or return $source;
  
  # Extract both the structure type and the class name of referent
  my $class_name;
  if ( "$source" =~ /^\Q$ref_type\E\=([A-Z]+)\(0x[0-9a-f]+\)$/ ) {
    $class_name = $ref_type;
    $ref_type = $1;
    # Some objects would prefer to clone themselves; check for clone_self().
    return $CloneCache{ $source } = $source->$CloneSelfMethod() 
				  if $source->can($CloneSelfMethod);
  }
  
  # To make a copy:
  # - Prepare a reference to the same type of structure;
  # - Store it in the cache, to avoid looping if it refers to itself;
  # - Tie in to the same class as the original, if it was tied;
  # - Assign a value to the reference by cloning each item in the original;
  
  my $copy;
  if ($ref_type eq 'HASH') {
    $CloneCache{ $source } = $copy = {};
    if ( my $tied = tied( %$source ) ) { tie %$copy, ref $tied }
    %$copy = map { ! ref($_) ? $_ : clone($_, $depth) } %$source;
  } elsif ($ref_type eq 'ARRAY') {
    $CloneCache{ $source } = $copy = [];
    if ( my $tied = tied( @$source ) ) { tie @$copy, ref $tied }
    @$copy = map { ! ref($_) ? $_ : clone($_, $depth) } @$source;
  } elsif ($ref_type eq 'REF' or $ref_type eq 'SCALAR') {
    $CloneCache{ $source } = $copy = \( my $var = "" );
    if ( my $tied = tied( $$source ) ) { tie $$copy, ref $tied }
    $$copy = clone($$source, $depth);
  } else {
    # Shallow copy anything else; this handles a reference to code, glob, regex
    $CloneCache{ $source } = $copy = $source;
  }
  
  # - Bless it into the same class as the original, if it was blessed;
  # - If it has a post-cloning initialization method, call it.
  if ( $class_name ) {
    bless $copy, $class_name;
    $copy->$CloneInitMethod() if $copy->can($CloneInitMethod);
  }
  
  return $copy;
}

1;

__END__

=head1 NAME

Clone::PP - Recursively copy Perl datatypes

=head1 SYNOPSIS

  use Clone::PP qw(clone);
  
  $item = { 'foo' => 'bar', 'move' => [ 'zig', 'zag' ]  };
  $copy = clone( $item );

  $item = [ 'alpha', 'beta', { 'gamma' => 'vlissides' } ];
  $copy = clone( $item );

  $item = Foo->new();
  $copy = clone( $item );

Or as an object method:

  require Clone::PP;
  push @Foo::ISA, 'Clone::PP';
  
  $item = Foo->new();
  $copy = $item->clone();

=head1 DESCRIPTION

This module provides a general-purpose clone function to make deep
copies of Perl data structures. It calls itself recursively to copy
nested hash, array, scalar and reference types, including tied
variables and objects.

=cut
