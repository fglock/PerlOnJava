use strict;
use warnings;
use Test::More;

my $output = '';
open my $capture, '>', \$output or die "open scalar handle: $!";

*slot_print_handle = $capture;
print {*slot_print_handle{IO}} 'glob slot output';
close $capture or die "close scalar handle: $!";

is($output, 'glob slot output',
    'print accepts an IO slot selected from a braced typeglob handle');

done_testing;
