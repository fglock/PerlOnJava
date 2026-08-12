use strict;
use warnings;

print "1..4\n";
my $number = 0;
sub check {
    my ($condition, $name) = @_;
    ++$number;
    print($condition ? "ok " : "not ok ", $number, " - $name\n");
}

my $pattern = qr{
    (?(DEFINE)
        (?<atom> [a-z]+)
        (?<pair> (?&atom) = (?&atom))
    )
    (?&pair)
}x;

check(scalar('left=right' =~ /^$pattern$/), 'DEFINE group can be called');
check(scalar('left=' !~ /^$pattern$/), 'subroutine call retains its body');
check(scalar('one=two' =~ /(?&pair)(?(DEFINE)(?<atom>[a-z]+)(?<pair>(?&atom)=(?&atom)))/),
    'DEFINE container may follow its call');
check(scalar('1=two' !~ /^$pattern$/), 'nested named calls preserve character classes');
