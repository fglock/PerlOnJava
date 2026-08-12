use strict;
use warnings;
use threads;
use threads::shared;

print "1..3\n";
my $number = 0;
sub check {
    my ($condition, $name) = @_;
    ++$number;
    print($condition ? "ok " : "not ok ", $number, " - ", $name, "\n");
}

sub redefine_from_string {
    eval 'no warnings; sub eval_string_target { 42 }; 1' or die $@;
    return eval_string_target();
}

sub call_eval_sub {
    return redefine_from_string();
}

my $thread = threads->create(sub { return call_eval_sub() });
check($thread->join == 42, 'child first-caller materializes cloned eval site');
check(!$thread->error, 'child eval leaves no thread error');
check(redefine_from_string() == 42, 'parent executes materialized eval site');
