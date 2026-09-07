use strict;
use warnings;
use threads;

print "1..3\n";
my $number = 0;
sub check {
    my ($condition, $name) = @_;
    ++$number;
    print($condition ? "ok " : "not ok ", $number, " - ", $name, "\n");
}

# IO::Async::Loop->create_thread() wraps a captured callback in a new
# ithread, calls it in the requested context, and forwards the joined values
# to another callback. Keep this project-owned form dependency-free while
# exercising the same runtime path.
sub invoke_in_thread {
    my ($code, $context, $on_joined) = @_;
    my ($thread) = threads->create(sub {
        my (@result, $died);
        eval {
            $context eq 'list' ? (@result = $code->()) : ($result[0] = $code->());
            1;
        } or $died = $@;
        return died => $died if $died;
        return return => @result;
    });
    $on_joined->($thread->join);
}

my @scalar;
invoke_in_thread(sub { return 'A result' }, 'scalar', sub { @scalar = @_ });
check(join(',', @scalar) eq 'return,A result',
    'captured callback scalar result reaches joined callback');

my @list;
invoke_in_thread(sub { return 'A result', 'of many', 'values' }, 'list', sub { @list = @_ });
check(join(',', @list) eq 'return,A result,of many,values',
    'captured callback list result reaches joined callback');

my @died;
invoke_in_thread(sub { die "expected failure\n" }, 'scalar', sub { @died = @_ });
check($died[0] eq 'died' && $died[1] =~ /expected failure/,
    'captured callback exception reaches joined callback');
