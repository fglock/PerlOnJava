use strict;
use warnings;
use Test::More;
use HTML::Parser;

# Regression test for HTML::Parser argspec `tokens`, `tokenN`, `tokenpos`.
# Before the fix, `buildArgs` had no case for `tokens`/`tokenpos` and
# the default branch silently emitted an empty string. This made the
# default comment handler installed by HTML/Parser.pm croak with
# "Can't use string ("") as an ARRAY ref while strict refs in use",
# which broke HTML-Tree's t/comment.t, t/parse.t, t/parsefile.t,
# t/construct_tree.t, t/split.t.

# tokens for a `comment` event: arrayref of [comment_body]
{
    my @collected;
    my $p = HTML::Parser->new(api_version => 3);
    $p->handler(comment => sub {
        my ($tokens) = @_;
        push @collected, $tokens;
    }, "tokens");
    $p->parse('<html><!-- hello --></html>');
    $p->eof;
    is(scalar(@collected), 1, 'one comment event fired');
    is(ref($collected[0]), 'ARRAY', 'tokens is an ARRAY ref');
    is(scalar(@{$collected[0]}), 1, 'comment tokens has one element');
    like($collected[0][0], qr/hello/, 'comment body is captured');
}

# tokens for a `start` event: arrayref of [tagname, k1, v1, k2, v2, ...]
{
    my @collected;
    my $p = HTML::Parser->new(api_version => 3);
    $p->handler(start => sub {
        my ($tokens) = @_;
        push @collected, $tokens;
    }, "tokens");
    $p->parse('<a href="x" class="y">');
    $p->eof;
    is(scalar(@collected), 1, 'one start event fired');
    is(ref($collected[0]), 'ARRAY', 'start tokens is an ARRAY ref');
    is($collected[0][0], 'a', 'tagname is first token');
    # attribute order should follow attrseq
    my %got = @{$collected[0]}[1 .. $#{$collected[0]}];
    is($got{href},  'x', 'href attribute captured');
    is($got{class}, 'y', 'class attribute captured');
}

# tokens for an `end` event: arrayref of [tagname]
{
    my @collected;
    my $p = HTML::Parser->new(api_version => 3);
    $p->handler(end => sub {
        my ($tokens) = @_;
        push @collected, $tokens;
    }, "tokens");
    $p->parse('<p>x</p>');
    $p->eof;
    ok(scalar(@collected) >= 1, 'at least one end event fired');
    is(ref($collected[0]), 'ARRAY', 'end tokens is an ARRAY ref');
    is($collected[0][0], 'p', 'end tagname is first token');
}

# tokenN argspec
{
    my @collected;
    my $p = HTML::Parser->new(api_version => 3);
    $p->handler(start => sub {
        push @collected, [@_];
    }, "token0,token1,token2");
    $p->parse('<a href="x">');
    $p->eof;
    is(scalar(@collected), 1, 'one start event fired (tokenN)');
    is($collected[0][0], 'a',    'token0 is tagname');
    is($collected[0][1], 'href', 'token1 is first attr name');
    is($collected[0][2], 'x',    'token2 is first attr value');
}

# tokenpos argspec returns a parallel arrayref (offsets are stubbed [0,0])
{
    my @collected;
    my $p = HTML::Parser->new(api_version => 3);
    $p->handler(start => sub {
        push @collected, [@_];
    }, "tokens,tokenpos");
    $p->parse('<a href="x">');
    $p->eof;
    is(scalar(@collected), 1, 'one start event fired (tokenpos)');
    is(ref($collected[0][0]), 'ARRAY', 'tokens is ARRAY ref');
    is(ref($collected[0][1]), 'ARRAY', 'tokenpos is ARRAY ref');
    is(scalar(@{$collected[0][1]}), scalar(@{$collected[0][0]}),
       'tokenpos has same length as tokens');
}

done_testing();
