use strict;
use warnings;
use Test::More;
use Storable qw[nfreeze thaw];

use Time::Moment;
use Time::Moment::Adjusters qw[OrthodoxEasterSunday];

my $moment = Time::Moment->from_string('2012-12-24T15:30:45.123456789+01:30');
is $moment->to_string, '2012-12-24T15:30:45.123456789+01:30',
    'round-trips a fixed-offset nanosecond instant';
is $moment->strftime('%Y-%m-%d %H:%M:%S %z'), '2012-12-24 15:30:45 +0130',
    'uses the shared POSIX formatter';
is $moment->with_rdn(719163)->to_string, '1970-01-01T15:30:45.123456789+01:30',
    'replaces the local Rata Die day';
is thaw(nfreeze($moment))->to_string, $moment->to_string,
    'Storable restores a Java-backed moment';
is Time::Moment->from_string('2024-01-01T09:00+02:00')
    ->with(OrthodoxEasterSunday)->to_string,
    '2024-05-05T09:00:00+02:00',
    'calculates Orthodox Easter with the upstream Julian computus';

done_testing;
