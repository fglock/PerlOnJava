use strict;
use warnings;
use Test::More;

eval q{'123' =~ tr/1/2/};
like($@, qr/^Can't modify constant item in transliteration \(tr\/\/\/\)/,
     'modifying transliteration rejects a constant');
eval q{'123' =~ tr/1/1/};
is($@, '', 'identity transliteration accepts a constant');
my ($s, @a);
for my $op ('chop', 'chomp') {
    for my $bind ('', '$s =~ ', '@a =~ ') {
        for my $replacement ('a', 'b') {
            for my $flags ('', 'r') {
                local $SIG{__WARN__} = sub {};
                eval "$op(${bind}tr/a/$replacement/$flags)";
                my $expected = $bind eq '@a =~ ' && $replacement eq 'b' && !$flags
                    ? 'private array in transliteration (tr///)'
                    : "transliteration (tr///) in $op";
                like($@, qr/^Can't modify \Q$expected\E/, 'result and target lvalue diagnostics');
            }
        }
    }
}
eval q{my $x; my $y = 'a'; chop($x = ($y =~ tr/a/b/))};
is($@, '', 'assignment containing transliteration is still an lvalue');
done_testing();
