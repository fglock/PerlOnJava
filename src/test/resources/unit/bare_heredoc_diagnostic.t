use strict;
use warnings;
use Test::More;

for my $source (
    q{$a = <<;},
    q{$a = <<~;},
    q{$a = <<~ ;},
) {
    my $result = eval $source;
    ok(!defined $result, 'bare heredoc marker fails');
    like($@,
        qr{\AUse of bare << to mean <<"" is forbidden at \(eval \d+\) line 1\.\n?\z},
        'bare heredoc diagnostic has no parser excerpt');
}

done_testing;
