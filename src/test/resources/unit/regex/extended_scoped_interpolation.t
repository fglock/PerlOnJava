use strict;
use warnings;
no warnings 'experimental::regex_sets';
use Test::More tests => 14;

my @positive = (
    [ q{(?[ (?^:(?[ [x] ])) ])}, 'one scoped wrapper' ],
    [ q{(?[ (?^:(?x:(?[ [x] ]))) ])}, 'two scoped wrappers' ],
    [ q{(?[ (?^:(?x:(?i:(?[ [x] ])))) ])}, 'three scoped wrappers' ],
);

for my $case (@positive) {
    my ($pattern, $name) = @$case;
    my $regex = eval "qr/$pattern/";
    is($@, '', "$name compiles");
    like('x', $regex, "$name matches its member");
    unlike('y', $regex, "$name excludes a nonmember");
}

my $restored = qr/(?[ (?^:(?i:(?[ [a] ]))) - [A] ])/;
like('a', $restored, 'nested ignore-case contributes lowercase member');
unlike('A', $restored,
        'nested ignore-case scope is restored before subtraction');

for my $pattern (
    q{(?[ (?^:(?x:[x])) ])},
    q{(?[ (?^:(?x:([x]))) ])},
    q{(?[ (?^:(?x:(?i:[x]))) ])},
) {
    my $ok = eval "qr/$pattern/; 1";
    ok(!$ok && $@ =~ /Expecting interpolated extended charclass/,
        'nested scoped chain still requires an extended-class leaf');
}
