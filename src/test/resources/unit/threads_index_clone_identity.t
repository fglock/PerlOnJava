use strict;
use warnings;
use threads;

print "1..4\n";
my $number = 0;
sub check {
    my ($condition, $name) = @_;
    ++$number;
    print($condition ? "ok " : "not ok ", $number, " - $name\n");
}

use constant mutable_ref => \our $referent;
my $constant_thread = threads->create(sub {
    my $was_scalar = ref(mutable_ref) eq 'SCALAR';
    package ThreadIndexStringifier {
        use overload '""' => sub { 'needle' };
    }
    bless \our $referent, 'ThreadIndexStringifier';
    return [$was_scalar, index('needle', mutable_ref)];
});
my $constant_result = $constant_thread->join;
check($constant_result->[0], 'reference constant remains a scalar reference');
check($constant_result->[1] == 0,
    'reference constant observes mutation of the cloned referent');

my $stored = 100;
{
    package ThreadIndexTie {
        require Tie::Scalar;
        our @ISA = qw(Tie::StdScalar);
        sub STORE { $stored = $_[1] }
    }
}
my $tie_thread = threads->create(sub {
    my $value;
    tie $value, 'ThreadIndexTie';
    $value = (index('foo', 'o') == -1);
    return $stored;
});
check($tie_thread->join == 0, 'lazy tie method mutates its cloned lexical');
check(($tie_thread->error || '') eq '', 'lazy tie method completes without error');
