use strict;
use warnings;
use Test::More;

our $strict_calls;

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
