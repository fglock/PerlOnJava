use strict;
use Test::More;
use Text::Markdown::Hoedown;
use Encode;

ok HOEDOWN_EXT_NO_INTRA_EMPHASIS;
isa_ok(Text::Markdown::Hoedown::Renderer::HTML->new(0, 0),
    'Text::Markdown::Hoedown::Renderer::HTML');
is markdown('# foo'), qq{<h1 id="toc_0">foo</h1>\n};
{
    use utf8;
    my $html = markdown('# あいう');
    ok Encode::is_utf8($html);
    is $html, qq{<h1 id="toc_0">あいう</h1>\n};
}
is markdown('http://mixi.jp', extensions => HOEDOWN_EXT_AUTOLINK),
    qq{<p><a href="http://mixi.jp">http://mixi.jp</a></p>\n};
done_testing;
