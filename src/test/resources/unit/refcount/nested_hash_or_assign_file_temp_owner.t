use strict;
use warnings;
use File::Temp;
use Test::More tests => 4;

my $cache;
$cache ||= {};
$cache->{capture} ||= {};
$cache->{capture}{spool} ||= File::Temp->new(UNLINK => 1);

ok(ref($cache->{capture}{spool}),
    'nested ||= keeps the File::Temp object in its hash slot');
ok(defined fileno($cache->{capture}{spool}),
    'stored File::Temp handle retains its descriptor');
ok(print { $cache->{capture}{spool} } "capture probe\n",
    'stored File::Temp handle remains writable');
ok(defined fileno($cache->{capture}{spool}),
    'stored handle descriptor remains available after write');
