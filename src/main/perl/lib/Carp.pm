package Carp;

#
# Original Carp module first appeared in Larry Wall's perl 5.000 distribution.
# Copyright (C) 1994-2013 Larry Wall
# Copyright (C) 2011, 2012, 2013 Andrew Main (Zefram) <zefram@fysh.org>
#
# This module is free software; you can redistribute it and/or modify it
# under the same terms as Perl itself.
#
# PerlOnJava implementation by Flavio S. Glock.
# The implementation is in: src/main/java/org/perlonjava/perlmodule/Carp.java
#

XSLoader::load( 'Carp' );

1;

__END__

=head1 NAME

Carp - alternative warn and die for modules

=head1 DESCRIPTION

This is the PerlOnJava implementation of Carp. The actual implementation
is in the Java backend.

=head1 AUTHOR

The Carp module first appeared in Larry Wall's perl 5.000 distribution.
Since then it has been modified by several of the perl 5 porters.
Andrew Main (Zefram) <zefram@fysh.org> divested Carp into an independent
distribution.

PerlOnJava implementation by Flavio S. Glock.

=head1 COPYRIGHT

Copyright (C) 1994-2013 Larry Wall

Copyright (C) 2011, 2012, 2013 Andrew Main (Zefram) <zefram@fysh.org>

=head1 LICENSE

This module is free software; you can redistribute it and/or modify it
under the same terms as Perl itself.

=cut

