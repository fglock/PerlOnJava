package Time::HiRes;

#
# Original Time::HiRes module authors:
# D. Wegscheid <wegscd@whirlpool.com>
# R. Schertler <roderick@argon.org>
# J. Hietaniemi <jhi@iki.fi>
# G. Aas <gisle@aas.no>
#
# Copyright (c) 1996-2002 Douglas E. Wegscheid. All rights reserved.
# Copyright (c) 2002, 2003, 2004, 2005, 2006, 2007, 2008 Jarkko Hietaniemi.
# All rights reserved.
#
# PerlOnJava implementation by Flavio S. Glock.
# The implementation is in: src/main/java/org/perlonjava/perlmodule/TimeHiRes.java
#

use strict;
use warnings;
use Exporter 'import';

our @EXPORT_OK = qw(usleep nanosleep ualarm gettimeofday tv_interval time sleep alarm);

require XSLoader;
XSLoader::load('Time::HiRes');

sub tv_interval {
    my ($start, $end) = @_;
    $end = [gettimeofday()] unless defined $end;
    return ($end->[0] - $start->[0]) + ($end->[1] - $start->[1]) / 1_000_000;
}

1;

__END__

=head1 NAME

Time::HiRes - High resolution time functions for PerlOnJava

=head1 AUTHOR

Original module authors:
D. Wegscheid <wegscd@whirlpool.com>
R. Schertler <roderick@argon.org>
J. Hietaniemi <jhi@iki.fi>
G. Aas <gisle@aas.no>

PerlOnJava implementation by Flavio S. Glock.

=head1 COPYRIGHT AND LICENSE

Copyright (c) 1996-2002 Douglas E. Wegscheid. All rights reserved.

Copyright (c) 2002, 2003, 2004, 2005, 2006, 2007, 2008 Jarkko Hietaniemi.
All rights reserved.

=cut
