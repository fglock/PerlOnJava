package Time::HiRes;

use strict;
use warnings;
use Exporter 'import';

our @EXPORT_OK = qw(usleep nanosleep ualarm gettimeofday tv_interval time sleep alarm);

# require XSLoader;
# XSLoader::load('Time::HiRes');

sub tv_interval {
    my ($start, $end) = @_;
    $end = [gettimeofday()] unless defined $end;
    return ($end->[0] - $start->[0]) + ($end->[1] - $start->[1]) / 1_000_000;
}

1;
