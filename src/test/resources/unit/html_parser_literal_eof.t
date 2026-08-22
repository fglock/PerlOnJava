use strict;
use warnings;

use HTML::Parser;
use Test::More;

my $html = <<'HTML';
<html>
<head><title>Fixed document</head>
<form>
<table>
<tr><select name="foo">
<option value="bar">Bar</option></select></td></tr>
</form>
</html>
HTML

my @events;
my $parser = HTML::Parser->new(api_version => 3);
$parser->handler(
    default => sub {
        my ($event, $text, $tag) = @_;
        if ($event eq 'start' || $event eq 'end') {
            push @events, ($event eq 'start' ? 'S' : 'E') . ":$tag";
        }
        elsif ($event eq 'text' && $text =~ /\S/) {
            $text =~ s/^\s+|\s+$//g;
            push @events, "T:$text";
        }
    },
    'event,text,tagname',
);
$parser->parse($html);
$parser->eof;

is_deeply(
    \@events,
    [
        'S:html', 'S:head', 'S:title', 'T:Fixed document', 'E:title',
        'E:head', 'S:form', 'S:table', 'S:tr', 'S:select', 'S:option',
        'T:Bar', 'E:option', 'E:select', 'E:td', 'E:tr', 'E:form',
        'E:html',
    ],
    'unterminated title closes at EOF and the remaining markup is tokenized',
);

is(scalar(grep { $_ eq 'S:form' } @events), 1,
    'form after an unterminated title is discovered');

done_testing;
