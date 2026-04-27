package ExtUtils::HasCompiler;

# PerlOnJava deterministic stub for ExtUtils::HasCompiler.
#
# Upstream this module probes the system for a working C compiler / linker
# and reports whether XS code can be built. PerlOnJava cannot build or load
# .so/.dll files (the JVM has no dlopen for native libraries we control), so
# we always answer "no". This is preferable to relying on the upstream
# probe, which on PerlOnJava just happens to return false because
# `$Config{usedl}` is empty — a fragile coincidence.
#
# See dev/modules/moose_support.md (Phase A) for the rationale.

use strict;
use warnings;

our $VERSION = '0.025';

use Exporter 'import';

our @EXPORT_OK = qw(
    can_compile_loadable_object
    can_compile_static_library
    can_compile_extension
);

our %EXPORT_TAGS = ( all => [@EXPORT_OK] );

sub can_compile_loadable_object { 0 }
sub can_compile_static_library  { 0 }
sub can_compile_extension       { 0 }

1;

__END__

=head1 NAME

ExtUtils::HasCompiler - PerlOnJava stub; reports no compiler available.

=head1 DESCRIPTION

PerlOnJava cannot build or load XS extensions. This stub answers C<0> for
all probes so distributions that conditionally fall back to pure-Perl
implementations choose that path.

=cut
