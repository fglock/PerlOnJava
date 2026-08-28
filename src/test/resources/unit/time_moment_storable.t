use strict;
use warnings;
use Test::More;
use Storable qw[nfreeze thaw];

use Time::Moment;

my $original = Time::Moment->from_string('2012-12-24T15:30:45.123456789-01:00');
my $restored = thaw(nfreeze($original));

isa_ok $restored, 'Time::Moment', 'Storable thaw restores the Java-backed object';
is $restored->to_string, $original->to_string,
    'Storable thaw preserves instant, precision, and fixed offset';

done_testing;
