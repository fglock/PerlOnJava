package DataHandleAbsolutePosition;
use strict;
use warnings;
use Test::More;
use Fcntl qw(SEEK_SET);

my $start = tell(DATA);
ok($start > 0, 'DATA tell is the absolute source-file position');

my $first = do { local $/; <DATA> };
ok(seek(DATA, $start, SEEK_SET), 'DATA seeks back to its absolute start');
my $second = do { local $/; <DATA> };

is($second, $first, 'DATA content can be read again after restoring tell');

done_testing;

__DATA__
payload
