package Object::Pad;

use strict;
use warnings;
use feature ();

our $VERSION = '0.66';

# PerlOnJava compiles the class, field, and method syntax natively.  Object::Pad
# normally installs those keywords through XS; its compatibility layer only
# needs to enable the equivalent lexical compiler feature here.  This covers
# the core syntax shared with Perl's class feature, including :param fields,
# method signatures, and :isa inheritance.
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

PerlOnJava implements the class syntax used by Object::Pad directly in its
compiler. This pragma enables that lexical syntax without loading the module's
XS keyword parser. Object::Pad-specific MOP and extension APIs are not provided.

=head1 AUTHOR

Object::Pad was written by Paul Evans <leonerd@leonerd.org.uk>.

=head1 COPYRIGHT AND LICENSE

Copyright 2026 Paul Evans. This compatibility pragma is free software; it may
be redistributed and/or modified under the same terms as Perl itself.

=cut
