package URI::Fast::IRI;

use strict;
use warnings;

require URI::Fast;
our $VERSION = '0.55';

our @ISA = qw(URI::Fast);

=head1 NAME

URI::Fast::IRI - IRI support for URI::Fast

=head1 DESCRIPTION

See L<URI::Fast/iri>.

=head1 AUTHOR

Jeff Ober <sysread@fastmail.fm>

=head1 COPYRIGHT AND LICENSE

This software is copyright (c) 2018 by Jeff Ober. This is free software; you
can redistribute it and/or modify it under the same terms as the Perl 5
programming language system itself.

=cut

sub clone { URI::Fast::IRI->new($_[0]) }

1;
