use strict;
use warnings;
use threads;
use threads::shared;

my $destroyed :shared = 0;

{
    package SharedFinalOwner;

    sub DESTROY {
        lock($destroyed);
        ++$destroyed;
    }
}

print "1..5\n";

my $inner = shared_clone(bless({ value => 7 }, 'SharedFinalOwner'));
my $root = shared_clone({ inner => $inner });
undef $inner;

# The temporary ordinary object passed to shared_clone is a distinct owner.
# Count only the canonical shared object from this point onward.
$destroyed = 0;

my $thread = threads->create(sub {
    my $view = $root->{inner};
    delete $root->{inner};
    undef $view;
    return $destroyed;
});

my $child_count = $thread->join;
print(($child_count == 1 ? "ok" : "not ok"),
      " 1 - final shared owner is destroyed in the child runtime\n");

undef $root;
print(($destroyed == 1 ? "ok" : "not ok"),
      " 2 - canonical shared object DESTROY runs exactly once\n");

print(($destroyed == $child_count ? "ok" : "not ok"),
      " 3 - parent observes the child destructor side effect\n");

my $second = shared_clone(bless({ value => 8 }, 'SharedFinalOwner'));
my $second_root = shared_clone({ inner => $second });
undef $second;
$destroyed = 1;

my $reader = threads->create(sub {
    my $view = $second_root->{inner};
    undef $view;
    return $destroyed;
});
print(($reader->join == 1 ? "ok" : "not ok"),
      " 4 - releasing a fetched view keeps canonical storage alive\n");

delete $second_root->{inner};
print(($destroyed == 2 ? "ok" : "not ok"),
      " 5 - canonical deletion destroys after fetched views are gone\n");
