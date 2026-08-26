use strict;
use warnings;
use utf8;
use Test::More tests => 2;

my $tld = qr/(?:com|みんな|中国|테스트|भारत|москва)/i;

my $text = "Visit site.みんな today";
$text =~ s{ (.*?) ($tld) | (.+?)$ }{defined $1 ? $1 : $3}gsex;
is($text, 'Visit site. today', 'substitution compiles an interpolated Unicode qr');

my $ascii = qr/(?:com|org)/i;
my $ascii_text = 'Visit site.com today';
$ascii_text =~ s{ (.*?) ($ascii) | (.+?)$ }{defined $1 ? $1 : $3}gsex;
is($ascii_text, 'Visit site. today', 'ASCII qr interpolation remains compatible');
