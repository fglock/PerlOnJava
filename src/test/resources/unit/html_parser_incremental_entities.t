use strict;
use warnings;
use Test::More;
use HTML::Parser;

# Keep the core-unit regression self-contained; HTML::TreeBuilder integration
# is exercised by HTML::Tree's upstream t/split.t acceptance run.

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

done_testing;
