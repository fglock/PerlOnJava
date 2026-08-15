use strict;
use warnings;
use threads;
use threads::shared;

print "1..8\n";

sub make_shared_reference {
    my $value :shared = 1;
    $value = shift;
    return \$value;
}

my $reference = make_shared_reference(4);

my $locked = eval {
    lock($$reference);
    ++$$reference;
    1;
};

print $locked ? "ok 1 - reassigned lexical remains lockable\n"
              : "not ok 1 - reassigned lexical remains lockable: $@\n";
print $$reference == 5 ? "ok 2 - reassigned shared value remains mutable\n"
                       : "not ok 2 - reassigned shared value remains mutable\n";
print is_shared($$reference) ? "ok 3 - reassigned lexical retains shared identity\n"
                             : "not ok 3 - reassigned lexical retains shared identity\n";

my ($first, $second) :shared = (7, 8);
my $list_locked = eval {
    lock($first);
    ++$first;
    1;
};

print $list_locked ? "ok 4 - initialized declaration-list slot is shared\n"
                   : "not ok 4 - initialized declaration-list slot is shared: $@\n";
print $first == 8 && is_shared($second)
    ? "ok 5 - shared attribute applies to every declaration-list slot\n"
    : "not ok 5 - shared attribute applies to every declaration-list slot\n";

my ($captured, $unused) :shared;
$captured = 10;

sub increment_captured_shared {
    lock($captured);
    return ++$captured;
}

my $parent_value = increment_captured_shared();
print $parent_value == 11
    ? "ok 6 - named sub captures shared declaration-list slot\n"
    : "not ok 6 - named sub captures shared declaration-list slot\n";

my $child_value = threads->create('increment_captured_shared')->join();
print $child_value == 12 && $captured == 12
    ? "ok 7 - child named sub retains captured shared storage\n"
    : "not ok 7 - child named sub retains captured shared storage\n";

my @slice_source :shared = (1, 2, 3, 4, 5);
my @slice_copy = @slice_source[1...4];
print join(':', @slice_copy) eq '2:3:4:5'
    ? "ok 8 - shared array slice preserves an inclusive three-dot range\n"
    : "not ok 8 - shared array slice preserves an inclusive three-dot range\n";
