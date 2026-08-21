use strict;
use warnings;
use utf8;
use Test::More;

my $wide_name = "<\x{100}name>";

pos($wide_name) = 0;
ok($wide_name =~ m{<([?/]?)([-\w:.\x80-\xff]+|!--)([^>]*)>}sg,
    'default charset XML pattern accepts a wide word character');
is($1, '', 'default XML marker capture is empty');
is($2, "\x{100}name", 'default XML name includes the wide character');
is($3, '', 'default XML trailing capture is empty');

pos($wide_name) = 0;
ok($wide_name !~ m{<([?/]?)([-\w:.\x80-\xff]+|!--)([^>]*)>}sga,
    '/a XML pattern rejects a wide word character');
ok(!defined(pos($wide_name)), 'failed /a global match clears pos');

pos($wide_name) = 0;
ok($wide_name !~ m{<([?/]?)([-\w:.\x80-\xff]+|!--)([^>]*)>}sgaa,
    '/aa XML pattern rejects a wide word character');

my $wide_suffix = "<x:xmpmeta\x{100}>";

pos($wide_suffix) = 0;
ok($wide_suffix =~ m{<(/?)x:xmpmeta([-\w:.\x80-\xff]*)(.*?(/?))>}sg,
    'default xmpmeta pattern treats a wide word character as a suffix');
is($2, "\x{100}", 'default xmpmeta suffix capture contains the wide character');
is($3, '', 'default xmpmeta trailing capture is empty');

pos($wide_suffix) = 0;
ok($wide_suffix =~ m{<(/?)x:xmpmeta([-\w:.\x80-\xff]*)(.*?(/?))>}sga,
    '/a xmpmeta pattern still matches the element');
is($1, '', '/a xmpmeta closing-marker capture is empty');
is($2, '', '/a xmpmeta suffix excludes the wide character');
is($3, "\x{100}", '/a xmpmeta trailing capture receives the wide character');
is($4, '', '/a xmpmeta self-closing capture is empty');
is($&, $wide_suffix, '/a xmpmeta whole match is preserved');
is_deeply([@-, @+], [0, 1, 10, 10, 11, 12, 1, 10, 11, 11],
    '/a xmpmeta capture offsets follow the native partition');

pos($wide_suffix) = 0;
ok($wide_suffix =~ m{<(/?)x:xmpmeta([-\w:.\x80-\xff]*)(.*?(/?))>}sgaa,
    '/aa xmpmeta pattern uses the same ASCII-restricted partition');
is($2 . '|' . $3, "|\x{100}", '/aa suffix and trailing captures are distinct');

my $preserved_pos = "xxxx<\x{100}name>";
pos($preserved_pos) = 4;
ok($preserved_pos !~ m{<([?/]?)([-\w:.\x80-\xff]+|!--)([^>]*)>}sgac,
    'failed /agc XML match rejects the wide name');
is(pos($preserved_pos), 4, '/c preserves pos after the failed native match');

my $byte_name = "<\x{e9}name>";
utf8::downgrade($byte_name, 1);
pos($byte_name) = 0;
ok($byte_name =~ m{<([?/]?)([-\w:.\x80-\xff]+|!--)([^>]*)>}sgaa,
    'explicit Latin-1 range still accepts a byte subject under /aa');
is($2, "\x{e9}name", 'byte-subject name capture is preserved');

done_testing;
