use strict;
use warnings;

use Test::More;

my @cases = (
    ['[\w-]',       { '-' => 1, A => 1, 5 => 1, ' ' => 0 }],
    ['[\d-]',       { '-' => 1, A => 0, 5 => 1, ' ' => 0 }],
    ['[\s-]',       { '-' => 1, A => 0, 5 => 0, ' ' => 1 }],
    ['[[:alpha:]-]', { '-' => 1, A => 1, 5 => 0, ' ' => 0 }],
    ['[\p{L}-]',    { '-' => 1, A => 1, 5 => 0, ' ' => 0 }],
    ['[^\w-]',      { '-' => 0, A => 0, 5 => 0, ' ' => 1 }],
);

for my $case (@cases) {
    my ($source, $expected) = @$case;
    my $warning = '';
    my $regex;
    {
        local $SIG{__WARN__} = sub { $warning .= $_[0] };
        $regex = eval "qr/^$source\$/";
    }
    is($@, '', "$source compiles");
    is($warning, '', "$source has no false-range warning");
    for my $subject ('-', 'A', '5', ' ') {
        is(0 + ($subject =~ $regex), $expected->{$subject},
            "$source membership for '$subject'");
    }
}

done_testing;
