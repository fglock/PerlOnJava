use strict;
use warnings;
use Test::More;

my @cases = (
    [ qr/([a-\d]+)/,                'za-9z', 'a-9', 'backslash d after a-dash' ],
    [ qr/([\d-z]+)/,                'a0-za', '0-z', 'backslash d before dash-z' ],
    [ qr/([\d-\s]+)/,              'a0- z', '0- ', 'backslash d through dash-space' ],
    [ qr/([a-[:digit:]]+)/,          'za-9z', 'a-9', 'POSIX digit after a-dash' ],
    [ qr/([[:digit:]-z]+)/,          '=0-z=', '0-z', 'POSIX digit before dash-z' ],
    [ qr/([[:digit:]-[:alpha:]]+)/, '=0-z=', '0-z', 'POSIX digit and alpha around dash' ],
);

for my $case (@cases) {
    my ($regex, $input, $want, $name) = @$case;
    my ($got) = $input =~ $regex;
    is($got, $want, $name);
}

done_testing;
