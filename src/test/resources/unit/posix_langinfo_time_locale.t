use strict;
use warnings;
use Test::More;

use POSIX qw(LC_TIME setlocale);
use I18N::Langinfo qw(langinfo MON_6 ABDAY_1);

my $previous = setlocale(LC_TIME);
my $selected = setlocale(LC_TIME, 'fr_FR.UTF-8');
plan skip_all => 'French locale is unavailable' unless defined $selected;

is(langinfo(MON_6), 'juin', 'langinfo follows the selected LC_TIME month names');
is(langinfo(ABDAY_1), 'dim.', 'langinfo follows the selected LC_TIME weekday names');

setlocale(LC_TIME, $previous) if defined $previous;
done_testing;
