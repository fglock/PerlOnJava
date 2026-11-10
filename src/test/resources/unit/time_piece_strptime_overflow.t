use strict;
use warnings;
use Test::More;
use Time::Piece;

my $time = Time::Piece->strptime(
    '2010-02-31T12:00:01', '%Y-%m-%dT%H:%M:%S'
);
is(
    $time->strftime('%Y-%m-%dT%H:%M:%S'),
    '2010-03-03T12:00:01',
    'strptime normalizes an out-of-range day like Perl timegm'
);

done_testing();
