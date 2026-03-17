package lib;

# Simplified lib.pm for PerlOnJava
# Provides basic @INC manipulation

use strict;
use warnings;

our $VERSION = '0.65';

sub import {
    shift;
    
    my %names;
    foreach (reverse @_) {
        my $path = $_;
        if ($path eq '') {
            require Carp;
            Carp::carp("Empty compile time value given to use lib");
            next;
        }
        
        if (-e $path && ! -d $path) {
            require Carp;
            Carp::carp("Parameter to use lib must be directory, not file");
        }
        unshift(@INC, $path);
    }
    
    # Remove trailing duplicates
    @INC = grep { ++$names{$_} == 1 } @INC;
    return;
}

sub unimport {
    shift;
    
    my %names;
    @names{@_} = (1) x @_;
    @INC = grep { !$names{$_} } @INC;
    return;
}

1;

__END__

=head1 NAME

lib - Manipulate @INC at compile time

=head1 SYNOPSIS

    use lib '/path/to/lib';
    no lib '/path/to/lib';

=head1 DESCRIPTION

This is a simplified version of lib.pm for PerlOnJava.

=cut
