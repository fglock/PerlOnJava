use strict;
use warnings;
use Test::More tests => 4;

{
    use lib 'src/test/resources/unit/lib';
    use Phase36Cname;

    is("\N{foo}", 'foo', 'nested lexical translator expands strings');
    ok('foo' =~ /^\N{foo}$/, 'nested lexical translator expands regex atoms');
    ok('WARN' =~ /^[\N{WARN}]$/, 'nested translator expands class sequences');
}

my $error = eval q{ qr/\N{foo}/; 1 } ? '' : $@;
like($error, qr/(?:Invalid Unicode character name|Unknown charname)/,
    'nested translator does not leak out of scope');
