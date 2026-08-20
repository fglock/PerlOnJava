use strict;
use warnings;
use Test::More;

my @cases = (
    [ qr/fooa++b/,     'fooaaaaab', 'possessive plus' ],
    [ qr/fooa*+b/,     'fooaaaaab', 'possessive star' ],
    [ qr/fooa{1,5}+b/, 'fooaaaaab', 'possessive bounded repeat' ],
    [ qr/fooa?+b/,     'fooab',     'possessive optional repeat' ],
);

for my $case (@cases) {
    my ($pattern, $input, $name) = @$case;
    my $match = $input =~ /$pattern/ ? $& : undef;
    is($match, $input, "$name retains its full literal region");
}

done_testing;
