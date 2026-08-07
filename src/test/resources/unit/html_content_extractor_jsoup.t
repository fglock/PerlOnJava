#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

BEGIN {
    plan skip_all => 'tests the PerlOnJava HTML::Content::Extractor backend'
        unless $^X eq 'jperl' || $^X =~ m{(?:^|[\\/])jperl(?:\.bat)?$};
}

use lib 'src/test/resources/unit/lib';
use HTML::Content::Extractor;

my $extractor = HTML::Content::Extractor->new;
$extractor->build_tree('<table><a>one<a><tbody>loose<tr><td>cell</table>');
my $tree = $extractor->get_tree;

is_deeply(
    [ map { [$_->{name}, $_->{level}] } @$tree ],
    [
        [html => 0], [head => 1], [body => 2],
        [' ' => 3], [a => 3], [' ' => 4], [a => 3],
        [table => 3], [tbody => 4], [tr => 5], [td => 6], [' ' => 7],
    ],
    'jsoup tree is adapted to the legacy anchor and table-jail rules',
);

$extractor->analyze('<div><a>navigation</a></div><main><img src="hero.jpg">Article text</main>');
is($extractor->get_main_text, 'Article text', 'main text excludes link-only navigation');
is($extractor->get_main_images->[0]{prop}{src}, 'hero.jpg', 'main image attributes are retained');

done_testing;
