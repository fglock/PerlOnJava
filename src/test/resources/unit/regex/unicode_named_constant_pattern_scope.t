use strict;
use warnings;
use Test::More tests => 5;

{
    use charnames ':full';
    ok('A' =~ '\N{LATIN CAPITAL LETTER A}',
        'constant string pattern uses the enclosing standard translator');
}

{
    use lib 'src/test/resources/unit/lib';
    use RegexImplementationCname;

    ok('xy' =~ 'x\N{EMPTY-STR}y',
        'nested constant string pattern retains its custom translator');
    ok('A' =~ /^\N{EVIL}$/,
        'literal match compiles its first stateful custom-name result');
    ok('AB' =~ qr/^\N{EVIL}$/,
        'following qr literal compiles the next custom-name result');
}

my $error = eval q{ '' =~ '\N{EMPTY-STR}'; 1 } ? '' : $@;
like($error, qr/(?:Invalid Unicode character name|Unknown charname)/,
    'constant pattern translator does not leak beyond lexical scope');
