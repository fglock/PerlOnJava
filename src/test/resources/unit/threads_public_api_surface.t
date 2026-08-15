use strict;
use warnings;
use threads 2.43;

my @methods = qw(
    create new async self tid object list join detach is_running is_joinable
    is_detached error exit kill wantarray get_stack_size set_stack_size
    set_thread_exit_only yield equal
);

print '1..', scalar(@methods) + 2, "\n";
my $test = 0;
sub check {
    my ($condition, $name) = @_;
    ++$test;
    print(($condition ? 'ok' : 'not ok'), " $test - $name\n");
}

check($threads::VERSION >= 2.43, 'threads compatibility version is current');
check($threads::threads, 'threads capability marker is enabled');
for my $method (@methods) {
    no strict 'refs';
    check(defined &{"threads::$method"}, "threads provides $method");
}
