package Devel::Cycle;
use strict;
use warnings;

our $VERSION = '1.12';

# No-op stub for PerlOnJava.
# The JVM uses tracing GC which handles circular references natively.
# Cycles are never a problem, so find_cycle always reports zero cycles.

use Exporter qw(import);

our @EXPORT = qw(find_cycle find_weakened_cycle);
our @EXPORT_OK = @EXPORT;

# Never calls callback = no cycles found
sub find_cycle          { }
sub find_weakened_cycle  { }

1;

__END__

=head1 NAME

Devel::Cycle - No-op stub for PerlOnJava

=head1 DESCRIPTION

This is a no-op implementation of Devel::Cycle for PerlOnJava.
The JVM uses tracing garbage collection which handles circular
references natively, so cycle detection is unnecessary.
C<find_cycle> and C<find_weakened_cycle> always report zero cycles.

=cut
