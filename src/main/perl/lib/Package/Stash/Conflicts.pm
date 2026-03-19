package Package::Stash::Conflicts;
use strict;
use warnings;

our $VERSION = '0.40';

# PerlOnJava stub - conflict checking not needed
# This module normally checks for conflicting versions of Package::Stash
# but since we're providing a single implementation, no conflicts possible

sub check_conflicts {
    # No-op - no conflicts to check in PerlOnJava
}

1;

__END__

=head1 NAME

Package::Stash::Conflicts - PerlOnJava stub for conflict checking

=head1 DESCRIPTION

This is a stub implementation for PerlOnJava. The original module checks
for conflicting versions of Package::Stash, but this is not needed in
PerlOnJava's environment.

=cut
