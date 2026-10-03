use strict;
use warnings;
use Test::More;
use HTML::Parser;
use HTML::TreeBuilder;

is(HTML::Entities::decode('&amp;'), '&',
    'HTML::Parser keeps the HTML::Entities decode alias available');

my $source = 'left  &#101; &aring &amp; right';

sub parsed_dtext {
    my ($text, @chunks) = @_;
    my @decoded;
    my $parser = HTML::Parser->new(api_version => 3);
    $parser->handler(text => sub { push @decoded, $_[0] }, 'dtext');
    $parser->parse($_) for @chunks;
    $parser->eof;
    return join '', @decoded;
}

my $expected = parsed_dtext($source, $source);
is($expected, 'left  e ' . chr(229) . ' & right',
    'numeric and semicolon-optional named entities match system Perl');
for my $split (0 .. length($source)) {
    my $actual = parsed_dtext($source, substr($source, 0, $split), substr($source, $split));
    is($actual, $expected, "decoded text is stable across a chunk boundary at $split");
}

my @characters = split //, $source;
is(parsed_dtext($source, @characters), $expected,
    'decoded text is stable when each source character arrives in its own chunk');

sub parsed_tree {
    my ($html, @chunks) = @_;
    my $tree = HTML::TreeBuilder->new;
    $tree->parse($_) for @chunks;
    $tree->eof;
    my $serialized = $tree->as_HTML;
    $tree->delete;
    return $serialized;
}

my $tree_source = "<p>Text  <b>bold</b>\n some &#101;ntities (&aring)</p>";
my $tree_expected = parsed_tree($tree_source, $tree_source);
for my $split (0 .. length($tree_source)) {
    my $actual = parsed_tree(
        $tree_source,
        substr($tree_source, 0, $split),
        substr($tree_source, $split),
    );
    is($actual, $tree_expected, "tree serialization is stable across a chunk boundary at $split");
}

is(
    parsed_tree($tree_source, split //, $tree_source),
    $tree_expected,
    'tree serialization is stable when each source character arrives in its own chunk',
);

done_testing;
