package deprecate;
use strict;
use warnings;

our $VERSION = '0.04';

# PerlOnJava stub for deprecate pragma
#
# This pragma warns when a module is loaded from Perl core directories,
# encouraging installation from CPAN. Since PerlOnJava doesn't have the
# traditional core/site library distinction, this is a no-op stub.

sub import {
    # No-op: PerlOnJava doesn't distinguish core vs site libraries
}

1;

__END__

=head1 NAME

deprecate - Perl pragma for deprecating the inclusion of a module in core

=head1 SYNOPSIS

    use deprecate;  # warn about future absence if loaded from core

=head1 DESCRIPTION

This is a PerlOnJava stub. The original pragma warns users when loading
modules from Perl core that will be removed in future releases.

Since PerlOnJava doesn't have the traditional core/site library directory
distinction, this pragma is a no-op.

=cut
