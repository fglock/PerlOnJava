use strict;
use warnings;
use Test::More tests => 2;
use bytes;

# Reduce HTML::Defang's byte-mode parser path: it consumes the start tag,
# tries to find a closing script tag, emits the defanged start tag, then
# appends the untouched remainder of the input.
my $html = "<SCRIPT SRC=http://ha.ckers.org/xss.js?<B>\n";
my ($tag) = $html =~ /\G(<SCRIPT\b[^>]*>)/gci;
ok(defined($tag), 'unterminated script start tag is consumed');

my $output = '<!--defang_SCRIPT SRC=http://ha.ckers.org/xss.js?<B-->';
if ($html =~ /\G(.*?)(?=<\/script\b)/gcsi) {
    my $contents = $1;
    $contents =~ s/<!--|-->|--//g;
    $output .= "<!-- $contents -->";
}
$html =~ /\G(.*)$/gcs;
$output .= $1 if defined $1;

is($output, "<!--defang_SCRIPT SRC=http://ha.ckers.org/xss.js?<B-->\n",
    'trailing newline follows the closed defanged tag comment');
