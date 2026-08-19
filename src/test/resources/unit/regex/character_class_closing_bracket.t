use strict;
use warnings;
use Test::More;

my @cases = (
    [ 'a]',       'a]',  1, 'closing bracket outside a class is literal' ],
    [ 'a[]]b',    'a]b', 1, 'leading closing bracket in a class is literal' ],
    [ 'a[^]b]c',  'a]c', 0, 'negated class excludes its leading bracket' ],
    [ 'a[^]b]c',  'adc', 1, 'negated class retains its ordinary members' ],
    [ '2(]*)?$\\1', '2', 1, 'closing bracket class composes with backreference' ],
);

for my $case (@cases) {
    my ($pattern, $subject, $expected, $name) = @$case;
    my @warnings;
    my $regex;
    {
        local $SIG{__WARN__} = sub { push @warnings, @_ };
        $regex = eval { qr/$pattern/ };
    }
    is($@, '', "$name compiles");
    is(scalar @warnings, 0, "$name has no warning");
    is(($subject =~ $regex) ? 1 : 0, $expected, $name);
}

done_testing;
