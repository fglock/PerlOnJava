use strict;
use warnings;
use Test::More;
use lib 'src/test/resources/unit/lib';

our ($byte_a, $byte_u, $unicode_a, $unicode_u);

{
    no utf8;
    use Phase36CnameMode;
    $byte_a = qr/^\N{FOO}$/a;
    $byte_u = qr/^\N{BAR}$/u;
}

{
    use utf8;
    use Phase36CnameMode;
    $unicode_a = qr/^\N{BAZ}$/a;
    $unicode_u = qr/^\N{QUX}$/u;
}

is_deeply(\@Phase36CnameMode::Modes, [qw(B B U U)],
    'custom translator input mode follows source bytes, not regex charset flags');
ok('A' =~ $byte_a, 'byte-source /a expansion matches');
ok('B' =~ $byte_u, 'byte-source /u expansion matches');
ok('A' =~ $unicode_a, 'Unicode-source /a expansion matches');
ok('B' =~ $unicode_u, 'Unicode-source /u expansion matches');

done_testing;
