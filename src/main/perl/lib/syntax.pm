package syntax;

use strict;
use warnings;

our $VERSION = '0.01';

sub import {
    shift;
    for my $feature (@_) {
        die "syntax: unsupported feature $feature\n" unless $feature eq 'method';
    }
    return;
}

sub unimport { return }

1;

__END__

=head1 NAME

syntax - PerlOnJava compatibility dispatcher for parser features

=head1 DESCRIPTION

The bundled dispatcher currently supports C<use syntax qw(method)> through
PerlOnJava's lexical parser extension. It does not provide the general CPAN
C<syntax> feature installation API.

=cut
