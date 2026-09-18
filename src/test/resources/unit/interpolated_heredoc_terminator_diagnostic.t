use strict;
use warnings;
use Test::More;

my $ok = eval q!"${<<foo";!;

ok(!$ok, 'unterminated interpolated heredoc fails');
like($@,
    qr{\ACan't find string terminator "foo" anywhere before EOF at \(eval \d+\) line 1\.\n\z},
    'unterminated interpolated heredoc reports its heredoc terminator');

my $directive_source = "#line 1 \"interpolated-heredoc\"\n" . '"${<<foo";';
$ok = eval $directive_source;

ok(!$ok, 'unterminated interpolated heredoc behind a line directive fails');
is($@,
    "Can't find string terminator \"foo\" anywhere before EOF at interpolated-heredoc line 1.\n",
    'interpolated heredoc keeps its outer source coordinate after re-tokenization');

done_testing;
