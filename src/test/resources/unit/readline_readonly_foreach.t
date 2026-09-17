use strict;
use warnings;
use Test::More tests => 2;

use constant roref => \2;
open FH, '<', $^X or die "open Perl executable: $!";
eval { for (roref) { $_ = <FH> } };
like($@, qr/Modification of a read-only value attempted/,
    'read-only foreach assignment fails');

open my $input, '<', $^X or die "open Perl executable: $!";
binmode $input;
ok(defined sysread($input, $_, 1),
    'sysread after the failed foreach assignment remains writable');
close $input or die "close Perl executable: $!";
close FH or die "close FH: $!";
