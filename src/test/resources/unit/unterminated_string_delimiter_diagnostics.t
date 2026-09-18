use strict;
use warnings;
use Test::More;

for my $case (
    [q{q/}, qr{\ACan't find string terminator "/" anywhere before EOF at \(eval \d+\) line 1\.\n\z}],
    [q{qw/}, qr{\ACan't find string terminator "/" anywhere before EOF at \(eval \d+\) line 1\.\n\z}],
    [q{'}, qr{\ACan't find string terminator "'" anywhere before EOF at \(eval \d+\) line 1\.\n\z}],
) {
    my ($source, $expected) = @$case;
    my $ok = eval $source;
    ok(!$ok, "unterminated $source fails");
    like($@, $expected, "unterminated $source reports its opening delimiter");
}

done_testing;
