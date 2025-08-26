# Copyright (c) 1997-2009 Graham Barr <gbarr@pobox.com>. All rights reserved.
# This program is free software; you can redistribute it and/or
# modify it under the same terms as Perl itself.
#
# Maintained since 2013 by Paul Evans <leonerd@leonerd.org.uk>

package List::Util;

use strict;
use warnings;
require Exporter;

our @ISA        = qw(Exporter);
our @EXPORT_OK  = qw(
  all any first min max minstr maxstr none notall product reduce reductions sum sum0
  sample shuffle uniq uniqint uniqnum uniqstr zip zip_longest zip_shortest mesh mesh_longest mesh_shortest
  head tail pairs unpairs pairkeys pairvalues pairmap pairgrep pairfirst
);
our $VERSION    = "1.68_01";
$VERSION =~ tr/_//d;

require XSLoader;
XSLoader::load('List::Util');

# Used by shuffle()
our $RAND;

# For objects returned by pairs()
sub List::Util::_Pair::key   { shift->[0] }
sub List::Util::_Pair::value { shift->[1] }
sub List::Util::_Pair::TO_JSON { [ @{+shift} ] }

# Functions implemented in Perl (not performance-critical or complex logic)

sub zip {
    my @arrays = @_;
    my @result;
    my $max_length = 0;
    
    # Find the maximum array length
    for my $array_ref (@arrays) {
        my $len = @$array_ref;
        $max_length = $len if $len > $max_length;
    }
    
    # Build result arrays
    for my $i (0 .. $max_length - 1) {
        my @tuple;
        for my $array_ref (@arrays) {
            push @tuple, $i < @$array_ref ? $array_ref->[$i] : undef;
        }
        push @result, \@tuple;
    }
    
    return @result;
}

sub zip_longest { goto &zip }

sub zip_shortest {
    my @arrays = @_;
    my @result;
    my $min_length;
    
    # Find the minimum array length
    for my $array_ref (@arrays) {
        my $len = @$array_ref;
        $min_length = $len if !defined($min_length) || $len < $min_length;
    }
    
    return () unless defined($min_length) && $min_length > 0;
    
    # Build result arrays
    for my $i (0 .. $min_length - 1) {
        my @tuple;
        for my $array_ref (@arrays) {
            push @tuple, $array_ref->[$i];
        }
        push @result, \@tuple;
    }
    
    return @result;
}

sub mesh {
    my @arrays = @_;
    my @result;
    my $max_length = 0;
    
    # Find the maximum array length
    for my $array_ref (@arrays) {
        my $len = @$array_ref;
        $max_length = $len if $len > $max_length;
    }
    
    # Build result by interleaving elements
    for my $i (0 .. $max_length - 1) {
        for my $array_ref (@arrays) {
            push @result, $i < @$array_ref ? $array_ref->[$i] : undef;
        }
    }
    
    return @result;
}

sub mesh_longest { goto &mesh }

sub mesh_shortest {
    my @arrays = @_;
    my @result;
    my $min_length;
    
    # Find the minimum array length
    for my $array_ref (@arrays) {
        my $len = @$array_ref;
        $min_length = $len if !defined($min_length) || $len < $min_length;
    }
    
    return () unless defined($min_length) && $min_length > 0;
    
    # Build result by interleaving elements
    for my $i (0 .. $min_length - 1) {
        for my $array_ref (@arrays) {
            push @result, $array_ref->[$i];
        }
    }
    
    return @result;
}

1;

__END__

=head1 NAME

List::Util - A selection of general-utility list subroutines

=head1 SYNOPSIS

    use List::Util qw(
      reduce any all none notall first reductions

      max maxstr min minstr product sum sum0

      pairs unpairs pairkeys pairvalues pairfirst pairgrep pairmap

      shuffle uniq uniqint uniqnum uniqstr head tail zip mesh
    );

=head1 DESCRIPTION

C<List::Util> contains a selection of subroutines that people have expressed
would be nice to have in the perl core, but the usage would not really be high
enough to warrant the use of a keyword, and the size so small such that being
individual extensions would be wasteful.

By default C<List::Util> does not export any subroutines.

This implementation uses Java for performance-critical functions while
maintaining full compatibility with the original Perl List::Util module.

=head1 COPYRIGHT

Copyright (c) 1997-2007 Graham Barr <gbarr@pobox.com>. All rights reserved.
This program is free software; you can redistribute it and/or
modify it under the same terms as Perl itself.

Recent additions and current maintenance by
Paul Evans, <leonerd@leonerd.org.uk>.

=cut

