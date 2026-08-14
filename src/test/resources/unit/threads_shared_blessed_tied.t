use strict;
use warnings;
use threads;
use threads::shared;

my $test = 0;
print "1..13\n";
sub ok {
    my ($condition, $name) = @_;
    ++$test;
    print(($condition ? "ok" : "not ok"), " $test - $name\n");
}

{
    package LocalTie;
    sub TIESCALAR { bless { value => $_[1] }, $_[0] }
    sub FETCH { $_[0]{value} }
    sub STORE { $_[0]{value} = $_[1] }
}

{
    package LocalArrayTie;
    sub TIEARRAY { bless { values => [$_[1]] }, $_[0] }
    sub FETCHSIZE { scalar @{$_[0]{values}} }
    sub STORESIZE { $#{$_[0]{values}} = $_[1] - 1 }
    sub FETCH { $_[0]{values}[$_[1]] }
    sub STORE { $_[0]{values}[$_[1]] = $_[2] }
}

{
    package LocalHashTie;
    sub TIEHASH { bless { values => { old => $_[1] } }, $_[0] }
    sub FETCH { $_[0]{values}{$_[1]} }
    sub STORE { $_[0]{values}{$_[1]} = $_[2] }
    sub EXISTS { exists $_[0]{values}{$_[1]} }
    sub FIRSTKEY { my $x = keys %{$_[0]{values}}; each %{$_[0]{values}} }
    sub NEXTKEY { each %{$_[0]{values}} }
}

{
    package SharedBlessed;
    sub value { $_[0]{value} }
}

my $object = bless({ value => 1 }, 'SharedBlessed');
my $object_shared = eval { share($object); 1 };
ok($object_shared, 'a blessed hash can be shared');
ok(is_shared($object), 'the blessed hash retains shared identity');

my $object_thread = threads->create(sub {
    my ($child_object) = @_;
    my $before = ref($child_object);
    $child_object->{value} = 7;
    my $value = $child_object->value;
    bless($child_object, 'ChildBlessed');
    return join(':', $before, $value, ref($child_object));
}, $object);
ok($object_thread->join eq 'SharedBlessed:7:ChildBlessed',
    'a child observes and can locally replace the blessing');
ok($object->{value} == 7, 'the parent observes the blessed shared mutation');
ok(ref($object) eq 'SharedBlessed', 'child blessing changes stay runtime-local');

tie my $tied, 'LocalTie', 0;
my $parent_tie = tied($tied);
my $tied_shared = eval { share($tied); 1 };
ok(!defined($parent_tie->{value}), 'sharing a tied scalar stores undef through its tie');
$tied = 1;
ok($tied_shared && is_shared($tied), 'a tied scalar can be shared');

my $tied_thread = threads->create(sub {
    my ($child_tied_ref) = @_;
    my $before = $$child_tied_ref;
    $$child_tied_ref = 9;
    return join(':', ref(tied($$child_tied_ref)), $before, $$child_tied_ref,
        tied($$child_tied_ref)->{value}, is_shared($$child_tied_ref) ? 1 : 0);
}, \$tied);
ok($tied_thread->join eq 'LocalTie:1:9:9:1',
    'the child has a runtime-local tie object with shared identity');
ok($tied == 1 && tied($tied)->{value} == 1,
    'child tie callbacks do not mutate the parent tie object');

tie my @tied_array, 'LocalArrayTie', 99;
share(@tied_array);
ok(ref(tied(@tied_array)) eq 'threads::shared::tie' && !@tied_array,
    'sharing a tied array replaces user magic with empty native shared storage');
$tied_array[0] = 4;
my $array_thread = threads->create(sub { $tied_array[1] = 6; scalar @tied_array });
ok($array_thread->join == 2 && join(':', @tied_array) eq '4:6',
    'converted shared array storage is visible in the parent');

tie my %tied_hash, 'LocalHashTie', 99;
share(%tied_hash);
ok(ref(tied(%tied_hash)) eq 'threads::shared::tie' && !keys(%tied_hash),
    'sharing a tied hash replaces user magic with empty native shared storage');
$tied_hash{x} = 10;
my $hash_thread = threads->create(sub { $tied_hash{y} = 12; scalar keys %tied_hash });
ok($hash_thread->join == 2 && $tied_hash{x} == 10 && $tied_hash{y} == 12,
    'converted shared hash storage is visible in the parent');
