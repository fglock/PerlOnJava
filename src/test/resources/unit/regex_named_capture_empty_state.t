use strict;
use warnings;
use Test::More;

'plain' =~ /plain/;
is_deeply([sort keys %+], [], '%+ is empty after a successful plain match');
is_deeply([sort keys %-], [], '%- is empty after a successful plain match');

'named' =~ /(?<word>named)/;
is($+{word}, 'named', '%+ exposes a named capture');
is_deeply($-{word}, ['named'], '%- exposes all values for a named capture');

'again' =~ /again/;
is_deeply([sort keys %+], [], 'plain match clears prior %+ names');
is_deeply([sort keys %-], [], 'plain match clears prior %- names');

done_testing;
