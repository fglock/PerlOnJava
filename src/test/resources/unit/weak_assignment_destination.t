use strict;
use warnings;
use Scalar::Util qw(isweak weaken);

print "1..4\n";

my $object = bless {}, 'WeakAssignmentDestination';
my %holder;

weaken($holder{ast} = $object);

print(!isweak($object)
    ? "ok 1 - assignment source remains strong\n"
    : "not ok 1 - assignment source remains strong\n");
print(isweak($holder{ast})
    ? "ok 2 - assignment destination is weak\n"
    : "not ok 2 - assignment destination is weak\n");

sub attach_weakly {
    my ($argument, $target) = @_;
    weaken($target->{ast} = $argument);
    return isweak($argument);
}

my $argument_object = bless {}, 'WeakAssignmentArgument';
my $argument_holder = {};
my $argument_became_weak = attach_weakly($argument_object, $argument_holder);

print(!$argument_became_weak && !isweak($argument_object)
    ? "ok 3 - argument alias remains strong\n"
    : "not ok 3 - argument alias remains strong\n");
print(isweak($argument_holder->{ast})
    ? "ok 4 - argument assignment destination is weak\n"
    : "not ok 4 - argument assignment destination is weak\n");
