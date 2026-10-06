package Object::Pad;

use strict;
use warnings;
use feature ();

our $VERSION = '0.66';

# PerlOnJava compiles class, field, method, and :accessor syntax natively.
# Object::Pad normally installs those keywords through XS; this compatibility
# layer enables the equivalent lexical compiler feature. The legacy `has`
# field spelling remains accepted for the advertised 0.66 compatibility level.
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

PerlOnJava implements core Object::Pad class syntax directly in its compiler,
including `class`, `field` (and the 0.66 `has` spelling), `method`, `:param`,
`:isa`, and scalar `:accessor` fields. This pragma enables that lexical syntax
without loading the module's XS keyword parser. Object::Pad-specific MOP and
other extension APIs are not provided.

=head1 AUTHOR

Object::Pad was written by Paul Evans <leonerd@leonerd.org.uk>.

=head1 COPYRIGHT AND LICENSE

Copyright 2026 Paul Evans. This compatibility pragma is free software; it may
be redistributed and/or modified under the same terms as Perl itself.

=cut
