use strict;
use warnings;
use Test::More tests => 18;

my $large_attr = ' GCamera:HdrPlusMakernote="' .
    ('SERSUAPvZDVtXnAeLOrjTFc6Z+BKrjw/q9nGM+nnwf9P' x 700) .
    '"';
my $xmp = '<x:xmpmeta xmlns:x="adobe:ns:meta/">' .
    '<rdf:Description' . $large_attr . '/>' .
    '</x:xmpmeta>';

ok($xmp =~ m{<([?/]?)([-\w:.\x80-\xff]+|!--)([^>]*)>}sg,
    'generic XML scan matches opening wrapper');
is($1, '', 'opening wrapper marker is empty');
is($2, 'x:xmpmeta', 'opening wrapper name is captured');
is($3, ' xmlns:x="adobe:ns:meta/"', 'opening wrapper attributes are captured');
is($&, '<x:xmpmeta xmlns:x="adobe:ns:meta/">', 'whole opening wrapper match is tracked');

ok($xmp =~ m{<([?/]?)([-\w:.\x80-\xff]+|!--)([^>]*)>}sg,
    'generic XML scan matches large description tag');
is($1, '', 'description marker is empty');
is($2, 'rdf:Description', 'description name is captured');
like($3, qr/^ GCamera:HdrPlusMakernote="/, 'description attributes start correctly');
like($3, qr/"\/$/, 'description attributes include self-closing slash');
is(pos($xmp), length('<x:xmpmeta xmlns:x="adobe:ns:meta/">') + length('<rdf:Description' . $large_attr . '/>'),
    'global match advances past large tag');

ok($xmp =~ m{<([?/]?)([-\w:.\x80-\xff]+|!--)([^>]*)>}sg,
    'generic XML scan matches closing wrapper');
is($1, '/', 'closing wrapper marker is captured');
is($2, 'x:xmpmeta', 'closing wrapper name is captured');
is($3, '', 'closing wrapper has no attributes');
is($&, '</x:xmpmeta>', 'whole closing wrapper match is tracked');
is($-[2], length($xmp) - length('x:xmpmeta>'), 'capture start offset is tracked');
is($+[2], $-[2] + length('x:xmpmeta'), 'capture end offset is tracked');
