use strict;
use warnings;
use threads;
use Scalar::Util qw(isweak refaddr weaken);

print "1..3\n";

{
    package ThreadWeakSlotRescuer;
    sub DESTROY {
        my ($self) = @_;
        $self->{source}{owner} = $self if $self->{source};
    }
}

my $owner = bless {}, 'ThreadWeakSlotRescuer';
my $source = { owner => $owner };
my $sibling = $owner;
weaken($source->{owner});
weaken($sibling);
$owner->{source} = $source;

my $result = threads->create(sub {
    undef $owner;
    my $observed = join ':',
        defined($source->{owner}) ? 1 : 0,
        isweak($source->{owner}) ? 0 : 1,
        (defined($sibling)
            && refaddr($sibling) == refaddr($source->{owner})) ? 1 : 0;
    $source->{owner}{source} = undef;
    $source->{owner} = undef;
    return $observed;
})->join;

$owner->{source} = undef;
$source->{owner} = undef;

my ($rescued, $strong, $sibling_live) = split /:/, $result;
print $rescued ? "ok 1 - DESTROY can rescue through its existing weak hash slot\n"
               : "not ok 1 - DESTROY can rescue through its existing weak hash slot\n";
print $strong ? "ok 2 - rescued hash slot becomes a strong owner\n"
              : "not ok 2 - rescued hash slot becomes a strong owner\n";
print $sibling_live ? "ok 3 - sibling weak references survive resurrection\n"
                    : "not ok 3 - sibling weak references survive resurrection\n";
