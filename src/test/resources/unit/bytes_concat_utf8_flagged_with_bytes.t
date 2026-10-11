use strict;
use warnings;
use Test::More tests => 3;
use utf8;
use Encode qw(encode_utf8 is_utf8);

my $content = encode_utf8("岡");
my $attribute = encode_utf8("ø");
Encode::_utf8_on($attribute);

my $output = '';
{
    use bytes;
    $output .= '<!--defang_p-->';
    $output .= $content;
    $output .= "<!--/defang_p-->\n";
    $output .= '<!--defang_a href="';
    $output .= $attribute;
    $output .= '"-->';
}

Encode::_utf8_on($output);
is(is_utf8($output) ? 1 : 0, 1, 'assembled output has the UTF-8 flag');
is($output,
   "<!--defang_p-->岡<!--/defang_p-->\n<!--defang_a href=\"ø\"-->",
   'byte captures and re-flagged attributes assemble without mojibake');
is(encode_utf8($output),
   encode_utf8("<!--defang_p-->岡<!--/defang_p-->\n<!--defang_a href=\"ø\"-->"),
   'assembled output encodes to the expected UTF-8 bytes');
