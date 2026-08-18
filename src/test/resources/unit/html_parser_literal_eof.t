use strict;
use warnings;

use HTML::Form;
use HTML::TokeParser;
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

my $parser = HTML::TokeParser->new(\$html);
my @events;
while (my $token = $parser->get_token) {
    my $type = $token->[0];
    if ($type eq 'S' || $type eq 'E') {
        push @events, "$type:$token->[1]";
    }
    elsif ($type eq 'T' && $token->[1] =~ /\S/) {
        my $text = $token->[1];
        $text =~ s/^\s+|\s+$//g;
        push @events, "T:$text";
    }
}

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

my @forms = HTML::Form->parse($html, base => 'http://localhost/');
is(scalar @forms, 1, 'form after an unterminated title is discovered');

done_testing;
