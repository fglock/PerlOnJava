#!perl
use strict;
use warnings;
use Test::More;

use Time::Moment;

is $Time::Moment::VERSION, '0.46', 'bundled provider reports the upstream version';

my $tm = Time::Moment->from_string('2012-12-24T15:30:45.123456789+01:30');
is $tm->to_string, '2012-12-24T15:30:45.123456789+01:30',
    'round-trips the upstream ISO representation';
is $tm->epoch, 1356357645, 'retains the represented instant';
is $tm->offset, 90, 'retains fixed offset minutes';
is $tm->plus_months(2)->to_string, '2013-02-24T15:30:45.123456789+01:30',
    'uses calendar arithmetic';
is $tm->with_offset_same_instant(0)->to_string, '2012-12-24T14:00:45.123456789Z',
    'changes offset while preserving instant';
is $tm->utc_rd_as_seconds, 63492040845, 'returns upstream Rata Die seconds';
is $tm->strftime('%Y-%m-%d %H:%M:%S %z'), '2012-12-24 15:30:45 +0130',
    'uses the shared Java formatter for POSIX directives';
ok $tm->is_after(Time::Moment->from_epoch(0)), 'compares instants';

done_testing;
