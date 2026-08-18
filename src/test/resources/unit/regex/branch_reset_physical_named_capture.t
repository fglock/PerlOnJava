use strict;
use warnings;
use Test::More tests => 3;

for my $case (
    ['1', '1 ', 'first branch publishes first physical named slot'],
    ['2', ' 2', 'second branch publishes second physical named slot'],
) {
    my ($subject, $expected, $name) = @$case;
    my $published = '';
    if ($subject =~ /(?|(?<digit>1)|(?<digit>2))/) {
        $published = join ' ', map { defined $_ ? $_ : '' } @{$-{digit}};
    }
    is($published, $expected, $name);
}

my $called = '';
if ('11' =~ /(?|(?<digit>1)|(?<digit>2))(?&digit)/) {
    $called = join ' ', map { defined $_ ? $_ : '' } @{$-{digit}};
}
is($called, '1 ', 'named call retains leftmost physical capture publication');
