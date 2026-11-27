package POSIX::strptime;

use 5.000;
use strict;

use XSLoader;
use vars qw($VERSION @ISA @EXPORT_OK);

$VERSION = '0.13';

require Exporter;
@ISA = qw(Exporter);
@EXPORT_OK = qw(strptime);

XSLoader::load __PACKAGE__, $VERSION;

if (not defined &POSIX::strptime) {
    *POSIX::strptime = \&strptime;
}

# Preloaded methods go here.

1;
__END__

=head1 NAME

POSIX::strptime - Perl extension to the POSIX date parsing strptime(3) function

=head1 SYNOPSIS

 ($sec, $min, $hour, $mday, $mon, $year, $wday, $yday, $isdst) = POSIX::strptime("string", "Format");

=head1 DESCRIPTION

Perl interface to strptime(3)

=head1 FUNCTIONS

=over 4

=item strptime

 ($sec, $min, $hour, $mday, $mon, $year, $wday, $yday) = POSIX::strptime(string, format);

The result for any value not extracted is not defined. Some platforms may
reliably return C<undef>, but this is dependent on the C<strptime(3)> function
in the underlying C library.

For example, only the following fields may be relied upon:

 my ($min, $hour) = ( POSIX::strptime( "01:23", '%H:%M' ) )[1,2];

 my ($mday, $mon, $year) = ( POSIX::strptime( "2010/07/16", '%Y/%m/%d' ) )[3,4,5];

Furthermore, not all platforms will set the C<$wday> and C<$yday> elements. If
these values are required, use C<mktime> and C<gmtime>:

 use POSIX qw( mktime );
 use POSIX::strptime qw( strptime );

 my ($mday, $mon, $year) = ( POSIX::strptime( "2010/07/16", '%Y/%m/%d' ) )[3,4,5];
 my $wday = ( gmtime mktime 0, 0, 0, $mday, $mon, $year )[6];

=back

=head1 SEE ALSO

strptime(3)

=head1 AUTHOR

Philippe M. Chiasson E<lt>gozer@cpan.orgE<gt>
Kim Scheibel E<lt>kim@scheibel.co.ukE<gt>

=head1 REPOSITORY

http://svn.ectoplasm.org/projects/perl/POSIX-strptime/trunk/

=head1 COPYRIGHT

Copyright 2005 by Philippe M. Chiasson E<lt>gozer@cpan.orgE<gt>.

This program is free software; you can redistribute it and/or
modify it under the same terms as Perl itself.

See F<http://www.perl.com/perl/misc/Artistic.html>


=cut
