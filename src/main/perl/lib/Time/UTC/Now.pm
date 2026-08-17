package Time::UTC::Now;

use 5.006;
use strict;
use warnings;
use parent 'Exporter';

our $VERSION = '0.013';
our @EXPORT_OK = qw(
    now_utc_rat now_utc_sna now_utc_flt now_utc_dec
    utc_day_to_mjdn utc_day_to_cjdn
);

require XSLoader;
XSLoader::load('Time::UTC::Now', $VERSION);

use constant _TAI_EPOCH_MJDN => 36204;
use constant _TAI_EPOCH_CJDN => 2436205;

sub utc_day_to_mjdn($) {
    return _TAI_EPOCH_MJDN + $_[0];
}

sub utc_day_to_cjdn($) {
    return _TAI_EPOCH_CJDN + $_[0];
}

1;

__END__

=head1 NAME

Time::UTC::Now - determine the current UTC time

=head1 DESCRIPTION

This PerlOnJava port preserves the Time::UTC::Now 0.013 API.  The Java
backend uses C<java.time.Instant>, which supplies UTC-like POSIX wall-clock
time with nanosecond fields but no trustworthy inaccuracy bound.  The third
result is therefore C<undef>, and calls which demand accuracy die.

=head1 AUTHOR AND COPYRIGHT

Original module by Andrew Main (Zefram) E<lt>zefram@fysh.orgE<gt>.

Copyright (C) 2006, 2007, 2009, 2010, 2012, 2017 Andrew Main.

This module is free software; you can redistribute it and/or modify it under
the same terms as Perl itself.

=cut
