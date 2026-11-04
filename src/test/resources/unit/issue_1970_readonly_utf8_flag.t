use strict;
use warnings;
use Test::More;

my $wide = "\x{2122}";
Internals::SvREADONLY($wide, 1);

ok(utf8::is_utf8($wide), 'read-only wide scalar retains its UTF-8 flag');

my $interpolate = sub { return "$wide" };
my $copy = $interpolate->();
my $concatenated = $wide . '';

ok(utf8::is_utf8($copy), 'closure interpolation preserves the UTF-8 flag');
ok(utf8::is_utf8($concatenated), 'concatenation preserves the UTF-8 flag');
is(ord($copy), 0x2122, 'closure interpolation preserves the wide character');

done_testing();
