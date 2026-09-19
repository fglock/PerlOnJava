use strict;
use warnings;
use Test::More;

my $ok = eval 'qr/\\N{/';
ok(!$ok, 'unterminated named character escape fails');
like($@, qr{\AMissing right brace on \\N\{\} or unescaped left brace after \\N at \(eval \d+\) line 1, within pattern},
     'diagnostic belongs to the regex source line');

done_testing;
