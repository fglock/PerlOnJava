use strict;
use warnings;
use Test::More;

my $ok = eval q{<<"foo};

ok(!$ok, 'unterminated quoted heredoc delimiter fails');
like($@,
    qr{\AUnterminated delimiter for here document at \(eval \d+\) line 1\.\n\z},
    'unterminated quoted heredoc delimiter reports the dedicated diagnostic');

done_testing;
