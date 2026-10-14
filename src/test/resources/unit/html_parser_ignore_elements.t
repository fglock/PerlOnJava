use strict;
use warnings;
use Test::More;
use HTML::Parser;

my @events;
my $parser = HTML::Parser->new(api_version => 3);
$parser->handler(start => sub { push @events, "start:$_[0]" }, 'tagname');
$parser->handler(end => sub { push @events, "end:$_[0]" }, 'tagname');
$parser->handler(text => sub { push @events, "text:$_[0]" }, 'text');
$parser->ignore_elements(qw(script style));
$parser->parse('<p>hi<script>x && <b>y</b></script>z<style>.x{}</style>w</p>');
$parser->eof;

is_deeply(\@events,
    ['start:p', 'text:hi', 'text:z', 'text:w', 'end:p'],
    'ignore_elements suppresses ignored element tags and their contents');

done_testing;
