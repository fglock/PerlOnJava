use strict;
use warnings;
use Test::More;

my $content = "alpha\nbeta\n";
open my $fh, '<', \$content or die "open scalar handle: $!";

my $count = read($fh, my $buffer, 16_384);
is($count, length($content), 'read returns scalar-backed byte count');
is($buffer, $content, 'read fills buffer from scalar-backed handle');
ok(eof($fh), 'scalar-backed handle reaches EOF');

done_testing;
