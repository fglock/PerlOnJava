package Object::Pad;

use strict;
use warnings;
use feature ();

our $VERSION = '0.800';

# PerlOnJava compiles the class, field, and method syntax natively. Object::Pad
# normally installs those keywords through XS; its compatibility layer only
# needs to enable the equivalent lexical compiler feature here. The 0.800
# compatibility boundary covers class/field/method declarations, field
# initializer expressions, :param, :reader, :writer, :accessor attributes,
# method signatures, ADJUST/BUILD blocks, and :isa inheritance. It does not
# provide the Object::Pad MOP, roles, custom field attributes, or XS APIs.
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

PerlOnJava advertises the supported native syntax subset as Object::Pad 0.800.
It implements classes, fields and field initializer expressions, methods,
method signatures, C<ADJUST> and C<BUILD> blocks, C<:isa> inheritance, and the
C<:param>, C<:reader>, C<:writer>, and C<:accessor> field attributes. The
C<:accessor> attribute generates a scalar reader-writer method that accepts
zero or one value argument. The legacy C<has> spelling is retained for
compatibility with older code.

This is a syntax compatibility level, not a claim of complete distribution
compatibility. Object::Pad MOP classes and APIs, roles, custom field attributes,
and XS extension APIs are unsupported and are not installed by the shim.

=head1 AUTHOR

Object::Pad was written by Paul Evans <leonerd@leonerd.org.uk>.

=head1 COPYRIGHT AND LICENSE

Copyright 2026 Paul Evans. This compatibility pragma is free software; it may
be redistributed and/or modified under the same terms as Perl itself.

=cut
