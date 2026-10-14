use strict;
use warnings;
use HTML::Parser;
use Test::More;

sub parsed_attributes {
    my ($attr_encoded) = @_;
    my $attributes;
    my $parser = HTML::Parser->new(api_version => 3, attr_encoded => $attr_encoded);
    $parser->handler(start => sub { $attributes = $_[1] }, 'tagname,attr');
    $parser->parse(q{<meta content="0; url=&#39;result.html&#39;" empty="" bare>});
    $parser->eof;
    return $attributes;
}

my $decoded = parsed_attributes(0);
is($decoded->{content}, "0; url='result.html'",
    'numeric character references in attributes are decoded by default');
is($decoded->{empty}, '', 'an explicitly empty attribute remains defined and empty');
is($decoded->{bare}, 'bare', 'boolean attributes retain their attribute-name value');

my $encoded = parsed_attributes(1);
is($encoded->{content}, '0; url=&#39;result.html&#39;',
    'attr_encoded preserves character references in attribute values');

SKIP: {
    eval { require HTML::TokeParser; 1 }
        or skip 'HTML::TokeParser is unavailable', 1;
    my $html = q{<meta http-equiv="refresh" content="0; url=&#39;result.html&#39;" />};
    my $toke_parser = HTML::TokeParser->new(\$html);
    my $tag = $toke_parser->get_tag('meta');
    is($tag->[1]{content}, "0; url='result.html'",
        'HTML::TokeParser decodes numeric entities in attribute values');
}

my $source = "\n <p title=\"&#39;\">x</p>";
my @position;
my $position_parser = HTML::Parser->new(api_version => 3);
$position_parser->handler(start => sub { @position = @_ },
    'line,column,offset,offset_end');
$position_parser->parse(substr($source, 0, 1));
$position_parser->parse(substr($source, 1));
$position_parser->eof;
is_deeply(\@position,
    [2, 1, 2, index($source, '>') + 1],
    'start event reports its source line, column, and byte offsets');

done_testing;
