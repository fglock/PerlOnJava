use strict;
use warnings;

use HTML::Parser;
use Test::More;

my @events;
my $parser = HTML::Parser->new(api_version => 3);
$parser->handler(
    default => sub { push @events, [@_] },
    'event,text,tagname',
);
$parser->parse('<B>Bold!</B> &amp; text');
$parser->eof;

is($events[0][0], 'start_document', 'default handler receives start_document');
is($events[-1][0], 'end_document', 'default handler receives end_document');
is(join('', map { $_->[1] } @events), '<B>Bold!</B> &amp; text',
    'default handler receives the complete original document text');
ok(!scalar(grep { $_->[0] eq 'text' && defined $_->[2] } @events),
    'text events do not report their text as a tag name');
is_deeply(
    [map { [$_->[0], $_->[2]] } grep { $_->[0] eq 'start' || $_->[0] eq 'end' } @events],
    [[start => 'b'], [end => 'b']],
    'default handler receives tag names for tag events',
);

done_testing;
