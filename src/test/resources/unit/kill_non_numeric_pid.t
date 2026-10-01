use strict;
use warnings;

print "1..4\n";
my $test = 0;
for my $case ([undef, 'undef'], ['', 'empty'], ['abcd', 'alphabetic']) {
    my ($pid, $name) = @$case;
    eval { kill 0, $pid };
    my $ok = $@ =~ /^Can't kill a non-numeric process ID/;
    print(($ok ? "ok" : "not ok"), " ", ++$test, " - rejects $name PID\n");
}

my $pid = $$ . ' ';
$pid =~ /(\d+)/;
my $ok = eval { kill 0, $1 };
print(($ok ? "ok" : "not ok"), " ", ++$test, " - accepts numeric PID in magic variable\n");
