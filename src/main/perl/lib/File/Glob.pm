package File::Glob;

use strict;
use warnings;

our $VERSION = '1.39';

use Exporter 'import';

our @EXPORT_OK = qw(
    glob
    bsd_glob
    GLOB_ERROR
    GLOB_CSH
    GLOB_NOMAGIC
    GLOB_QUOTE
    GLOB_TILDE
    GLOB_BRACE
    GLOB_NOCHECK
    GLOB_NOSORT
    GLOB_NOSPACE
    GLOB_ABEND
    GLOB_ALPHASORT
    GLOB_ALTDIRFUNC
);

our %EXPORT_TAGS = (
    'glob' => [ qw(
        glob
        bsd_glob
        GLOB_ERROR
        GLOB_CSH
        GLOB_NOMAGIC
        GLOB_QUOTE
        GLOB_TILDE
        GLOB_BRACE
        GLOB_NOCHECK
        GLOB_NOSORT
        GLOB_NOSPACE
        GLOB_ABEND
        GLOB_ALPHASORT
        GLOB_ALTDIRFUNC
    ) ],
);

# Constants for glob flags
use constant {
    GLOB_ERROR     => 0,
    GLOB_CSH       => 1,
    GLOB_NOMAGIC   => 2,
    GLOB_QUOTE     => 4,
    GLOB_TILDE     => 8,
    GLOB_BRACE     => 16,
    GLOB_NOCHECK   => 32,
    GLOB_NOSORT    => 64,
    GLOB_NOSPACE   => 128,
    GLOB_ABEND     => 256,
    GLOB_ALPHASORT => 512,
    GLOB_ALTDIRFUNC => 1024,
};

# bsd_glob implementation - use Perl's built-in glob for now
sub bsd_glob {
    my $pattern = shift;
    my $flags = shift || 0;
    
    # For now, just use Perl's built-in glob
    # In the future, we could implement the flags properly
    return CORE::glob($pattern);
}

# Regular glob - just use built-in
sub glob {
    my $pattern = shift;
    return CORE::glob($pattern);
}

1;

__END__

=head1 NAME

File::Glob - Perl extension for BSD glob routine

=head1 SYNOPSIS

  use File::Glob ':glob';
  
  @list = bsd_glob('*.txt');

=head1 DESCRIPTION

This is a minimal implementation of File::Glob for PerlOnJava.
It provides basic glob functionality using Perl's built-in glob operator.

=head1 FUNCTIONS

=over 4

=item bsd_glob($pattern [, $flags])

Implements BSD-style globbing. Currently uses Perl's built-in glob.

=item glob($pattern)

Simple wrapper around Perl's built-in glob.

=back

=head1 CONSTANTS

Various GLOB_* constants are exported for compatibility.

=head1 AUTHOR

PerlOnJava Project

=cut

