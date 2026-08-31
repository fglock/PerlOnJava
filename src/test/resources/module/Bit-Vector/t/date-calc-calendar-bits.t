use strict;
use warnings;
use Test::More tests => 21;

use Bit::Vector;

# Date::Calendar::Year marks the day-of-year entries that are holidays using
# Bit::Vector.  Keep this focused representation here: Date::Calc itself is
# an external CPAN consumer, not a bundled module.
my $calendar = Bit::Vector->new(366);

is($calendar->bit_test(0), 0, 'a new calendar starts with no marked days');
$calendar->Bit_On(0);
$calendar->Bit_On(59);
$calendar->Bit_On(365);
is($calendar->bit_test(0), 1, 'marks the first day');
is($calendar->contains(59), 1, 'contains aliases bit_test');
is($calendar->bit_test(365), 1, 'marks the leap-year final day');

$calendar->Bit_Off(59);
is($calendar->bit_test(59), 0, 'clears a calendar day');
is($calendar->bit_flip(59), 1, 'flip returns the new set value');
is($calendar->flip(59), 0, 'flip alias returns the new clear value');

$calendar->Bit_Copy(59, 1);
is($calendar->bit_test(59), 1, 'copies a true bit value');
$calendar->Bit_Copy(59, 0);
is($calendar->bit_test(59), 0, 'copies a false bit value');

$calendar->LSB(1);
$calendar->MSB(1);
is($calendar->lsb, 1, 'reads the least significant calendar bit');
is($calendar->msb, 1, 'reads the most significant calendar bit');

my $workdays = Bit::Vector->new(366);
$workdays->Interval_Fill(0, 4);
$workdays->AndNot($workdays, $calendar);
is($workdays->Norm, 4, 'removes a calendar holiday from a workday interval');
$workdays->Interval_Fill(0, 4);
$workdays->And($workdays, $calendar);
is($workdays->Norm, 1, 'finds a calendar holiday via intersection');
$workdays->Empty;
is($workdays->Norm, 0, 'clears the calendar work space');
$workdays->Interval_Fill(2, 4);
is_deeply([ $workdays->Interval_Scan_inc(3) ], [ 3, 4 ],
    'incremental scan starts at the requested day');
is_deeply([ $workdays->Interval_Scan_dec(3) ], [ 2, 3 ],
    'decremental scan ends at the requested day');

for my $method (qw(Bit_On Bit_Off bit_test bit_flip Bit_Copy)) {
    my $ok = eval {
        $method eq 'Bit_Copy'
            ? $calendar->$method(366, 1)
            : $calendar->$method(366);
        1;
    };
    ok(!$ok && $@ =~ /index out of range/, "$method rejects an index past the calendar");
}
