use strict;
use warnings;
use Test::More;

my $ok = eval q!"${<<foo";!;

ok(!$ok, 'unterminated interpolated heredoc fails');
like($@,
    qr{\ACan't find string terminator "foo" anywhere before EOF at \(eval \d+\) line 1\.\n\z},
    'unterminated interpolated heredoc reports its heredoc terminator');

done_testing;
