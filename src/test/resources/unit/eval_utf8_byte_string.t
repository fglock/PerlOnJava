use strict;
use warnings;
use Test::More tests => 1;

my $source = "use utf8; my %h = (тест => 123); \$h{тест};";
utf8::downgrade($source);
my $result = eval $source;
is($result, 123, 'eval decodes UTF-8 identifiers in a byte-backed source');
