use strict;
use warnings;
use Test::More tests => 7;
use utf8;
use Encode qw(is_utf8);

my $html = '<a href="http://blah.com/ø">岡</a>';
ok(is_utf8($html), 'parser input starts as Unicode');
my $expected_content = chr(0x5CA1);
my ($attribute, $content, $output);

{
    use bytes;
    ($attribute, $content) = $html =~ /href="([^"]+)">([^<]+)/;
    ok(!is_utf8($attribute), 'byte-mode capture is unflagged before re-flagging');
    ok(!is_utf8($content), 'byte-mode content capture is unflagged before re-flagging');
    Encode::_utf8_on($attribute);
    Encode::_utf8_on($content);
    $output = '<a href="' . $attribute . '">' . $content . '</a>';
    Encode::_utf8_on($output);
}
is($attribute, 'http://blah.com/ø', 're-flagged attribute has the original characters');
is($content, $expected_content, 're-flagged content capture has the original character');
is($output, $html, 'assembled parser output remains equal to its Unicode input');
ok(is_utf8($output), 'assembled parser output has the UTF-8 flag');
