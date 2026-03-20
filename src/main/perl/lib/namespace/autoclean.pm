package namespace::autoclean;

use strict;
use warnings;

our $VERSION = '0.31';

# namespace::autoclean stub for PerlOnJava
#
# This is a no-op stub that provides the interface but skips cleanup.
#
# Problem: The real namespace::autoclean uses subname() to detect whether a
# function was defined in the current package or imported. Functions where
# subname() returns a different package are cleaned. However, this breaks
# modules like DateTime::TimeZone that import Try::Tiny's try/catch and use
# them internally.
#
# Solution: Skip all cleanup. The cleanup is just namespace hygiene - it
# prevents imported functions from being callable as methods. Since PerlOnJava
# is typically used in controlled environments where this isn't a concern,
# skipping cleanup is safe and enables modules like DateTime to work.

sub import {
    # Accept all arguments but do nothing
    # Real signature: ($class, %args) where %args can include -cleanee, -also, -except
    return;
}

# Provide the subname function in case anything checks for it
sub subname {
    my ($coderef) = @_;
    # Return a reasonable default - the B module integration isn't always available
    return ref($coderef) eq 'CODE' ? '__ANON__' : undef;
}

1;

__END__

=head1 NAME

namespace::autoclean - PerlOnJava stub (no cleanup performed)

=head1 SYNOPSIS

    package MyClass;
    use namespace::autoclean;
    use Some::Exporter qw(imported_function);
    
    sub method { imported_function('args') }
    
    # In real namespace::autoclean, imported_function would be removed
    # In this stub, it remains available (both as function and method)

=head1 DESCRIPTION

This is a stub implementation of namespace::autoclean for PerlOnJava. It
provides the interface but performs no actual cleanup.

=head2 Why a stub?

The real namespace::autoclean removes imported functions from a package's
namespace to keep it clean. It uses C<Sub::Util::subname()> or the B module
to detect which functions were imported vs defined locally.

This breaks modules like DateTime::TimeZone that:

=over 4

=item 1. Import functions from Try::Tiny (try, catch)

=item 2. Use namespace::autoclean

=item 3. Call those functions internally

=back

The imported try/catch get cleaned, causing "Undefined subroutine" errors.

=head2 Why is skipping cleanup safe?

The cleanup is purely cosmetic - it prevents imported functions from being
callable as methods on objects. In most use cases:

=over 4

=item * Methods are called by name, not discovered dynamically

=item * Imported functions aren't accidentally called as methods

=item * The slight namespace pollution is harmless

=back

=head1 PARAMETERS

The following parameters are accepted but ignored:

=over 4

=item -cleanee => $package

=item -also => \@subs or qr/pattern/

=item -except => \@subs or qr/pattern/

=back

=head1 SEE ALSO

L<namespace::clean> - The module this is based on

L<DateTime::TimeZone> - A module that benefits from this stub

=head1 COPYRIGHT

This is a PerlOnJava compatibility stub.

=cut
