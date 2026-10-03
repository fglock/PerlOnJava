use strict;
use warnings;
use Test::More;

package Local::LanguageFunctional;
sub break (&$) {
    my ($predicate, $items) = @_;
    for my $item (@$items) {
        return $item if $predicate->($item);
    }
    return;
}

package main;
BEGIN { *main::break = \&Local::LanguageFunctional::break }

my $result = break { $_[0] >= 4 } [1 .. 6];
is($result, 4, 'an imported break sub receives a block and list argument');

done_testing;
