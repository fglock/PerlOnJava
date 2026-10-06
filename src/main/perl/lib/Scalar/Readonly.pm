package Scalar::Readonly;

use 5.006;
use strict;
use warnings;

require Exporter;
our @ISA = qw(Exporter);
our %EXPORT_TAGS = (all => [qw(readonly readonly_on readonly_off)]);
our @EXPORT_OK = @{ $EXPORT_TAGS{all} };
our @EXPORT = ();
our $VERSION = '0.03';

require XSLoader;
XSLoader::load('Scalar::Readonly', $VERSION);

1;

__END__

=head1 NAME

Scalar::Readonly - functions for controlling whether any scalar variable is read-only

=head1 DESCRIPTION

This is a PerlOnJava Java-backed implementation of Scalar::Readonly 0.03.
It preserves the CPAN module's XS API for querying and changing scalar
read-only flags.

=head1 AUTHOR

Philippe M. Chiasson, E<lt>gozer@cpan.orgE<gt>

=head1 COPYRIGHT AND LICENSE

Copyright (C) 2004 by Philippe M. Chiasson

This library is free software; you can redistribute it and/or modify it under
the same terms as Perl itself, either Perl version 5.8.3 or, at your option,
any later version of Perl 5 you may have available.

=cut
