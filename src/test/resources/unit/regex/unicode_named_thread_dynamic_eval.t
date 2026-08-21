use strict;
use warnings;
use Test::More;
use threads;
use lib 'src/test/resources/unit/regex';

sub child_result {
    {
        use LocalDynamicCharnames;

        my $direct = eval q{'xfooy' =~ /x\N{FOO}y/};
        my $nested = eval q{eval q{'bar' =~ /^\N{BAR}$/}};
        my $empty  = eval q{'xy' =~ /^x\N{EMPTY}y$/};
        my $first  = eval q{'A' =~ /^(\N{EVIL})$/ && $1};
        my $second = eval q{'B' =~ /^(\N{EVIL})$/ && $1};
        return join ':', map { defined $_ ? $_ : '<undef>' }
                $direct, $nested, $empty, $first, $second, $@;
    }
}

is(threads->create(\&child_result)->join,
        '1:1:1:A:B:',
        'dynamic and nested eval retain lexical charnames in a child');
is(threads->create(\&child_result)->join,
        '1:1:1:A:B:',
        'a repeated child receives an independent callback-state clone');

done_testing;
