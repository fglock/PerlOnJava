use strict;
use warnings;
use Config;
use threads qw(:all stringify stack_size 65536 exit thread_only);

print "1..10\n";
my $number = 0;
sub check {
    my ($condition, $name) = @_;
    ++$number;
    print($condition ? "ok " : "not ok ", $number, " - ", $name, "\n");
}

check($Config{useithreads} eq 'define', 'Config advertises ithreads');
check($Config{usethreads} eq 'define', 'Config advertises threads');
check($Config{usemultiplicity} eq 'define', 'Config advertises multiplicity');
check(defined &async, 'async is exported by default');
check(defined &yield, ':all exports yield');
check(!defined yield(), 'yield returns undef');
check("" . threads->self eq threads->self->tid,
    'stringify option renders the thread id');

my $unknown = eval q{ package UnknownImport; threads->import('bogus'); 1 };
check(!$unknown && $@ =~ /threads: Unknown import option: bogus/,
    'unknown import options are rejected');
my $missing = eval q{ package MissingImport; threads->import('stack_size'); 1 };
check(!$missing && $@ =~ /threads: Missing argument for option: stack_size/,
    'missing import arguments are rejected');

my $thread = async { threads->self->tid };
check($thread->join > 0, 'activated async creates an isolated ithread');
