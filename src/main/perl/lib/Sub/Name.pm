package Sub::Name;
# ABSTRACT: (Re)name a sub

#
# PerlOnJava implementation of Sub::Name
# Original module by Matthijs van Duin <xmath@cpan.org>
# 
# The implementation is in:
#   src/main/java/org/perlonjava/runtime/perlmodule/SubName.java
#

use strict;
use warnings;

our $VERSION = '0.28';

use Exporter ();
*import = \&Exporter::import;

our @EXPORT = qw(subname);
our @EXPORT_OK = @EXPORT;

XSLoader::load('Sub::Name', $VERSION);

1;

__END__

=head1 NAME

Sub::Name - (Re)name a sub

=head1 SYNOPSIS

    use Sub::Name;

    subname $name, $subref;

    $subref = subname foo => sub { ... };

=head1 DESCRIPTION

This module has only one function, which is also exported by default:

=head2 subname NAME, CODEREF

Assigns a new name to referenced sub. If package specification is omitted in
the name, then the current package is used. The return value is the sub.

The name is only used for informative routines (caller, Carp, etc). You won't
be able to actually invoke the sub by the given name. To allow that, you need
to do glob-assignment yourself.

Note that for anonymous closures (subs that reference lexicals declared outside
the sub itself) you can name each instance of the closure differently, which
can be very useful for debugging.

=head1 SEE ALSO

=over 4

=item *

L<Sub::Identify> - for getting information about subs

=item *

L<Sub::Util> - set_subname is another implementation of C<subname>

=back

=head1 COPYRIGHT AND LICENSE

This software is copyright (c) 2004, 2008 by Matthijs van Duin, all rights reserved;
copyright (c) 2014 cPanel Inc., all rights reserved.

This program is free software; you can redistribute it and/or modify
it under the same terms as Perl itself.

=cut
