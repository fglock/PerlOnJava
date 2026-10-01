use strict;
use warnings;
use Test::More;

my $text = "a\n\nb\n\n=head1 X\n\nhello\n";
my @paragraphs;
while ($text =~ /(?:(.*?)((?:\s*\n){2,}))|(.+$)/sg) {
    push @paragraphs, defined($1) ? $1 : $3;
}

is(scalar(@paragraphs), 4, 'repeated whitespace finds each blank-line separator');
is_deeply(\@paragraphs, ['a', 'b', '=head1 X', "hello\n"],
    'lazy paragraph captures backtrack to every separator');

done_testing;
