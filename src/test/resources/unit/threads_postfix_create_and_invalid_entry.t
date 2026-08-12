use strict;
use warnings;
use threads;

print "1..4\n";
my $number = 0;
sub check {
    my ($condition, $name) = @_;
    ++$number;
    print($condition ? "ok " : "not ok ", $number, " - ", $name, "\n");
}

my $postfix = create threads sub {
    my $value = 'postfix';
    return sub { return sub { return $value } };
}=>->join->()();
check($postfix eq 'postfix', 'fat comma permits a postfix thread call chain');

my $invalid = threads->create({}, []);
check(defined($invalid), 'reference-valued invalid entry creates a thread');
$invalid->join;
check($invalid->error =~ /Not a CODE reference/,
    'invalid entry failure is retained on the thread');
check(!$invalid->is_running, 'invalid entry thread reaches a terminal state');
