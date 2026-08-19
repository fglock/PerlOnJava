use strict;
use warnings;
use Test::More;
use lib 'src/test/resources/unit/lib';

{
    use charnames ':full';
    use re '/aa';
    unlike('k', qr/(?i:\N{KELVIN SIGN})/,
        'ordinary named scalar preserves Perl /aa folding behavior');
}

use Phase36Cname;

$Phase36Cname::Evil = 'A';
my $first = eval q{qr/^\N{EVIL}$/};
is($@, '', 'first identical custom-name source compiles');
is($Phase36Cname::Evil, 'AB',
    'literal validation and runtime compilation share one first expansion');
ok('A' =~ $first, 'first custom-name regex keeps its lexical expansion');

my $second = eval q{qr/^\N{EVIL}$/};
is($@, '', 'second identical custom-name source compiles');
is($Phase36Cname::Evil, 'ABC',
    'second logical compilation invokes the translator exactly once');
ok('AB' =~ $second, 'second custom-name regex keeps its distinct expansion');
ok('A' !~ $second, 'identical source does not reuse the first expansion');
ok('A' =~ $first, 'later compilation does not contaminate the first regex');

done_testing;
