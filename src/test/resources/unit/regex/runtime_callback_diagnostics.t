use strict;
use warnings;
use re 'eval';

print "1..4\n";

my @cases = (
    [q!qr'a(?{f()+'!, qr/^Missing right curly or square bracket/,
        'unterminated callback expression reports its missing brace'],
    [q!qr'a(?{{1}+'!, qr/^Missing right curly or square bracket/,
        'unterminated nested callback block reports its missing brace'],
    [q!qr' (?{(^{})'!, qr/^syntax error/,
        'an earlier callback expression error wins over structural EOF'],
    [q!qr'a(?{"{"}})b'!, qr/^Sequence \(\?\{\.\.\.\}\) not terminated with '\)'/,
        'extra callback brace reports the missing group terminator'],
);

my $number = 0;
for my $case (@cases) {
    my ($source, $expected, $name) = @$case;
    eval $source;
    ++$number;
    print $@ =~ $expected
        ? "ok $number - $name\n"
        : "not ok $number - $name: $@\n";
}
