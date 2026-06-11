use strict;
use warnings;
use Test::More tests => 9;

my $open = '<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="test">';
my $payload = '<rdf:RDF><rdf:Description GCamera:HdrPlusMakernote="' .
    ('SERSUAPvZDVtXnAeLOrjTFc6Z+BKrjw/q9nGM+nnwf9P' x 700) .
    '"/></rdf:RDF>';
my $xmp = $open . $payload . '</x:xmpmeta>';

pos($xmp) = length($open);
ok($xmp =~ m{<(/?)x:xmpmeta([-\w:.\x80-\xff]*)(.*?(/?))>}sg,
    'slash-heavy XMP wrapper scan matches closing tag');
is($1, '/', 'closing slash capture is set');
is($2, '', 'name suffix capture is empty');
is($3, '', 'attribute capture is empty for closing tag');
is($4, '', 'self-closing slash capture is empty');
is(pos($xmp), length($xmp), 'global match advances pos to end of closing tag');
is($&, '</x:xmpmeta>', 'whole match is closing xmpmeta tag');
is($-[1], length($xmp) - length('</x:xmpmeta>') + 1, 'capture start offset is tracked');
is($+[1], $-[1] + 1, 'capture end offset is tracked');
