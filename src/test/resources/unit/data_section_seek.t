use strict;
use warnings;
use Test::More tests => 3;

my $first = <DATA>;
is($first, "payload-one\n", 'DATA initially starts after its marker');

ok(seek(DATA, 0, 0), 'DATA can seek back to the source-file start');
while (<DATA>) {
    last if /^__DATA__$/;
}
is(<DATA>, "payload-one\n", 'rewound DATA exposes the marker and payload');

__DATA__
payload-one
payload-two
