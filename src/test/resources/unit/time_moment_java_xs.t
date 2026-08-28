use strict;
use warnings;
use Test::More;

use Time::Moment;

my $tm = Time::Moment->from_string('2012-12-24T15:30:45.123456789+01:30');
is $tm->to_string, '2012-12-24T15:30:45.123456789+01:30', 'round-trips an offset ISO instant';
is $tm->epoch, 1356357645, 'preserves the instant';
is $tm->offset, 90, 'preserves offset minutes';
is $tm->plus_months(2)->to_string, '2013-02-24T15:30:45.123456789+01:30', 'uses calendar arithmetic';
is $tm->with_offset_same_instant(0)->to_string, '2012-12-24T14:00:45.123456789Z', 'changes display offset without changing instant';
is $tm->strftime('%Y-%m-%d %H:%M:%S %z'), '2012-12-24 15:30:45 +0130', 'formats through the shared Java formatter';
ok $tm->is_after(Time::Moment->from_epoch(0)), 'compares instants';
is $tm->with_week(1)->week, 1, 'changes ISO week while preserving its weekday';
is $tm->with_day_of_week(1)->day_of_week, 1, 'changes ISO weekday';
is $tm->with_rdn(719163)->to_string, '1970-01-01T15:30:45.123456789+01:30', 'changes Rata Die day locally';
ok $tm->is_leap_year, 'reports Gregorian leap years';
is $tm->length_of_week_year, 52, 'reports ISO weeks in the week-based year';

use Time::Moment::Adjusters qw[OrthodoxEasterSunday];
is Time::Moment->from_string('2024-01-01T09:00+02:00')->with(OrthodoxEasterSunday)->to_string,
    '2024-05-05T09:00:00+02:00', 'calculates Orthodox Easter using the Julian computus';

done_testing;
