use strict;
use warnings;
use re 'eval';

print "1..2\n";

my @values = (1, 2, 3);
my $plain = qr/@values(?{})/;
print "$plain" =~ /\Q1 2 3(?{})\E/
    ? "ok 1 - callback marker is hidden in regex stringification\n"
    : "not ok 1 - callback marker is hidden in regex stringification\n";

my $dynamic = qr/(??{ 'x' })/;
print "$dynamic" !~ /DYNAMIC:\d+/
    ? "ok 2 - dynamic callback marker is hidden in regex stringification\n"
    : "not ok 2 - dynamic callback marker is hidden in regex stringification\n";
