use strict;
use warnings;
use Test::More;
use HTML::Parser;

my @attributes;
my $parser = HTML::Parser->new(api_version => 3);
$parser->handler(start => sub {
    my ($tag, @attrs) = @_;
    @attributes = ($tag, @attrs);
}, 'tagname,@attr');
$parser->parse('<p id="p1" class="intro" hidden></p>');
$parser->eof;

is_deeply(\@attributes,
    ['p', id => 'p1', class => 'intro', hidden => 'hidden'],
    '\@attr expands to ordered attribute name/value pairs');

my @self_closing_attributes;
my $empty_tag_parser = HTML::Parser->new(
    api_version => 3,
    empty_element_tags => 1,
);
$empty_tag_parser->handler(start => sub {
    my ($tag, @attrs) = @_;
    @self_closing_attributes = ($tag, @attrs);
}, 'tagname,@attr');
$empty_tag_parser->parse('<img src="logo.png" />');
$empty_tag_parser->eof;
is_deeply(\@self_closing_attributes, ['img', src => 'logo.png'],
    'empty_element_tags omits the self-closing slash from attributes');

my @comment;
my $comment_parser = HTML::Parser->new(api_version => 3);
$comment_parser->handler(comment => sub { @comment = @_ },
    'tagname,line,column,text');
$comment_parser->parse('<!-- html-lint directive -->');
$comment_parser->eof;
is($comment[0], ' html-lint directive ',
    'comment events expose their body as tagname');
is($comment[3], '<!-- html-lint directive -->',
    'comment events retain the full text token');

my @default_slash;
my $default_parser = HTML::Parser->new(api_version => 3);
$default_parser->handler(start => sub {
    my ($tag, @attrs) = @_;
    @default_slash = ($tag, @attrs);
}, 'tagname,@attr');
$default_parser->parse('<img src="logo.png" />');
$default_parser->eof;
is_deeply(\@default_slash,
    ['img', src => 'logo.png', '/' => '/'],
    'without empty_element_tags the slash remains a boolean attribute');

done_testing;
