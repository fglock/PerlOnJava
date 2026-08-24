use strict;
use warnings;
use Test::More;

my @numbers = 2 .. 4;
my @letters = qw(b c d);

my $got = eval {
    for my $item (@letters, undef, @numbers) {
        ++$item;
    }
    1;
};

is($got, undef, 'modifying an undef rvalue through a foreach alias fails');
like($@, qr/^Modification of a read-only value attempted/,
    'foreach alias reports the standard read-only error');
is("@letters", 'c d e', 'values before the undef rvalue were modified');
is("@numbers", '2 3 4', 'values after the undef rvalue were not modified');

for my $item (@numbers[0, 1, 0]) {
    ++$item;
}
is("@numbers", '4 4 4', 'array slices remain foreach lvalues');

for (3) {
    eval { ${\$_} = 4 };
    like($@, qr/^Modification of a read-only value attempted/,
        'a reference to a numeric literal foreach alias remains read-only');
    is($_, 3, 'failed reference assignment preserves the numeric literal');
}

for ('literal') {
    eval { ${\$_} = 'changed' };
    like($@, qr/^Modification of a read-only value attempted/,
        'a reference to a string literal foreach alias remains read-only');
    is($_, 'literal', 'failed reference assignment preserves the string literal');
}

for (undef) {
    is(\$_, \undef,
        'a canonical undef foreach alias retains reference identity');
    eval { ${\$_} = 'changed' };
    like($@, qr/^Modification of a read-only value attempted/,
        'a reference to an undef foreach alias remains read-only');
    ok(!defined($_), 'failed reference assignment preserves undef');
}

done_testing;
