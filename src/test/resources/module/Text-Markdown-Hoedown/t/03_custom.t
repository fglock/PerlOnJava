use strict;
use warnings;
use utf8;
use Test::More;
use Text::Markdown::Hoedown;

my $cb = Text::Markdown::Hoedown::Renderer::Callback->new();
ok $cb;
$cb->doc_header(sub { "<doctype html>\n<html>\n" });
$cb->normal_text(sub { $_[0] });
$cb->entity(sub { $_[0] });
$cb->header(sub { "<h$_[1]>$_[0]</h$_[1]>\n" });
$cb->codespan(sub { "<code>$_[0]</code>\n" });
$cb->paragraph(sub { "<p>$_[0]</p>\n" });
$cb->autolink(sub { qq{<a href="$_[0]">$_[0]</a>} });
$cb->linebreak(sub { '<br>' });
$cb->underline(sub { "<u>$_[0]</u>" });
$cb->raw_html(sub { $_[0] });
$cb->image(sub {
    defined $_[1]
        ? qq!<img title="$_[1]" alt="$_[2]" src="$_[0]">!
        : qq!<img alt="$_[2]" src="$_[0]">!;
});
$cb->link(sub {
    defined $_[2]
        ? qq!<a title="$_[2]" href="$_[1]">$_[0]</a>!
        : qq!<a href="$_[1]">$_[0]</a>!;
});
$cb->doc_footer(sub { "</body></html>\n" });

my $md = Text::Markdown::Hoedown::Markdown->new(
    HOEDOWN_EXT_AUTOLINK | HOEDOWN_EXT_UNDERLINE | HOEDOWN_EXT_FOOTNOTES,
    16,
    $cb,
);

is $md->render(<<'MARKDOWN'), <<'HTML';
# hoge
fuga & hige
http://mixi.jp/
`hoge()`
<b>bold</b>
_under_
![Alt text](/path/to/img.jpg "Optional title")
![Alt text](/path/to/img.jpg)

This is [an example](http://example.com/ "Title") inline link.

I get 10 times more traffic from [Google] [1] than from
[Yahoo] [2] or [MSN] [3].

 [1]: http://google.com/        "Google"
 [2]: http://search.yahoo.com/  "Yahoo Search"
 [3]: http://search.msn.com/    "MSN Search"
MARKDOWN
<doctype html>
<html>
<h1>hoge</h1>
<p>fuga & hige
<a href="http://mixi.jp/">http://mixi.jp/</a>
<code>hoge()</code>

<b>bold</b>
<u>under</u>
<img title="Optional title" alt="Alt text" src="/path/to/img.jpg">
<img alt="Alt text" src="/path/to/img.jpg"></p>
<p>This is <a title="Title" href="http://example.com/">an example</a> inline link.</p>
<p>I get 10 times more traffic from <a title="Google" href="http://google.com/">Google</a> than from
<a title="Yahoo Search" href="http://search.yahoo.com/">Yahoo</a> or <a title="MSN Search" href="http://search.msn.com/">MSN</a>.</p>
</body></html>
HTML

done_testing;
