use strict;
use warnings;
use Test::More;

our $strict_calls;
our $delimiter_calls;

{
    use re 'eval';

    for my $backslashes (0 .. 3) {
        $delimiter_calls = 0;
        my $runtime = ('\\' x $backslashes)
            . '~(?{ ++$main::delimiter_calls })';
        my $subject = ('\\' x int($backslashes / 2)) . '~';
        my $matched = eval { $subject =~ /^$runtime$/ };
        is($@, '',
            "delimiter case $backslashes has no synthetic-source error");
        ok($matched,
            "runtime source preserves $backslashes backslashes before delimiter");
        is($delimiter_calls, 1,
            "callback after delimiter case $backslashes executes once");
    }
}

{
    no warnings 'experimental::re_strict';
    use re 'strict';
    use re 'eval';

    $strict_calls = 0;
    my $runtime = '(?{ ++$main::strict_calls })';
    my $matched = eval { '' =~ /^$runtime$/ };
    is($@, '',
        'runtime callback has no synthetic-source error under lexical re strict');
    ok($matched,
        'runtime callback compiles under lexical re strict');
    is($strict_calls, 1,
        'private re strict state does not enter executable source text');
}

done_testing;
