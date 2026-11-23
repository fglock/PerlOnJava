use strict;
use warnings;
use Test::More;
use HTML::Parser;

my $attributes;
my $parser = HTML::Parser->new(api_version => 3);
$parser->handler(start => sub { $attributes = $_[1] }, 'tagname,attr');
$parser->parse(q{<a href="https://example.test/?a=1&amp;b=%2Ftmp%2Fa">file</a>});
$parser->eof;
is($attributes->{href}, 'https://example.test/?a=1&b=%2Ftmp%2Fa',
    'HTML::Parser decodes ampersands in href values and keeps percent escapes');

BEGIN {
    eval { require HTML::TreeBuilder; 1 }
        or plan skip_all => 'HTML::TreeBuilder is not installed';
}

my $tree = HTML::TreeBuilder->new;
$tree->parse_content(q{<a href="https://example.test/?a=1&amp;b=%2Ftmp%2Fa">file</a>});
my $link = $tree->look_down(_tag => 'a');
is($link->attr('href'), 'https://example.test/?a=1&b=%2Ftmp%2Fa',
    'href entities are decoded while URL percent escapes remain unchanged');
$tree->delete;

done_testing;
