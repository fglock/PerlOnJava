package Object::Pad;

use strict;
use warnings;
use feature ();

our $VERSION = '0.66';

# PerlOnJava compiles the class, field, and method syntax natively. Object::Pad
# normally installs those keywords through XS; its compatibility layer only
# needs to enable the equivalent lexical compiler feature here. This includes
# the legacy `has` field spelling, :param, :reader, :writer, and :accessor field
# attributes, method signatures, and :isa inheritance.
sub import {
    feature->import('class');
    warnings->unimport('experimental::class');
    return;
}

sub unimport {
    feature->unimport('class');
    return;
}

1;

__END__

=head1 NAME

Object::Pad - PerlOnJava compatibility pragma for native class syntax

=head1 DESCRIPTION

PerlOnJava implements the class, field, and method syntax used by Object::Pad
directly in its compiler. Alongside C<field>, this compatibility version also
accepts the legacy C<has> spelling supported by Object::Pad 0.66. Supported
field attributes include C<:param>, C<:reader>, C<:writer>, and C<:accessor>.
The C<:accessor> attribute generates a scalar reader-writer method that accepts
zero or one value argument. Object::Pad-specific MOP and other extension APIs
are not provided.

=head1 AUTHOR

Object::Pad was written by Paul Evans <leonerd@leonerd.org.uk>.

=head1 COPYRIGHT AND LICENSE

Copyright 2026 Paul Evans. This compatibility pragma is free software; it may
be redistributed and/or modified under the same terms as Perl itself.

=cut
