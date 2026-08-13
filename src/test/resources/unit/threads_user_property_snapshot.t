use strict;
use warnings;

use threads;

print "1..3\n";
my $test_number = 0;
sub ok_ {
    my ($condition, $name) = @_;
    ++$test_number;
    print($condition ? "ok " : "not ok ", "$test_number - $name\n");
}

my $calls = 0;
sub IsPhaseTwentySeven {
    ++$calls;
    return "0041";
}

ok_(eval(q{"A" =~ /\p{IsPhaseTwentySeven}/}) && !$@,
    'parent resolves a user-defined Unicode property');
ok_($calls == 1, 'parent caches the property definition');

my $child = threads->create(sub {
    my $matched = eval(q{"A" =~ /\p{IsPhaseTwentySeven}/});
    return [$matched ? 1 : 0, $calls, "$@"];
});
my $result = $child->join;

ok_(ref($result) eq 'ARRAY'
        && $result->[0] == 1
        && $result->[1] == 1
        && $result->[2] eq '',
    'ithread inherits the resolved property cache without calling its cloned sub');
