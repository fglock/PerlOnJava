use strict;
use warnings;
use threads;
use threads::shared;

print "1..15\n";
my $test = 0;
sub ok {
    my ($condition, $name) = @_;
    ++$test;
    print(($condition ? "ok" : "not ok"), " $test - $name\n");
}

sub rejected {
    my ($operation) = @_;
    local $@;
    eval { $operation->(); 1 };
    return $@ =~ /Invalid value for shared scalar/;
}

my @array :shared;
my %hash :shared;
my $private_hash = { value => 1 };
my $private_array = [1];

ok(rejected(sub { $array[0] = $private_hash }) && !defined($array[0]),
    'array element assignment rejects a private hash reference before mutation');
ok(rejected(sub { $hash{bad} = $private_array }) && !defined($hash{bad}),
    'hash element assignment rejects a private array reference before storing it');

@array = (10);
ok(rejected(sub { push @array, 20, $private_hash, 30 })
        && join(',', @array) eq '10,20',
    'push preserves earlier values but stops at the invalid reference');

@array = ();
ok(rejected(sub { @array = (1, $private_array, 3) })
        && (@array == 1 || @array == 2) && $array[0] == 1
        && !defined($array[1]),
    'list assignment does not publish the invalid reference');

%hash = ();
ok(rejected(sub { @hash{qw(first bad last)} = (1, $private_hash, 3) })
        && $hash{first} == 1 && !defined($hash{bad}) && !exists($hash{last}),
    'hash slice assignment stops before later values');

my $blessed = bless({ value => 2 }, 'PrivateObject');
ok(rejected(sub { $hash{object} = $blessed }) && !defined($hash{object}),
    'blessed private references are rejected');

my $cycle = [];
push @$cycle, $cycle;
ok(rejected(sub { $hash{cycle} = $cycle }) && !defined($hash{cycle}),
    'cyclic private references are rejected');

$array[0] = undef;
$array[1] = 42;
$array[2] = "text";
$hash{number} = 7;
ok(!defined($array[0]) && $array[1] == 42 && $array[2] eq 'text'
        && $hash{number} == 7,
    'undef and ordinary scalar values remain valid');

my $shared_hash = shared_clone({ value => 5 });
my $shared_array = shared_clone([6]);
$array[3] = $shared_hash;
$hash{array} = $shared_array;
ok(is_shared($array[3]) && is_shared($hash{array}),
    'references whose referents are shared remain valid');

my $worker = threads->create(sub {
    ++$array[3]{value};
    push @{$hash{array}}, 7;
    return "$array[3]{value}:" . join(',', @{$hash{array}});
});
ok($worker->join eq '6:6,7', 'child can mutate accepted shared referents');
ok($shared_hash->{value} == 6 && join(',', @$shared_array) eq '6,7',
    'accepted nested mutations are visible through the original shared views');
ok($private_hash->{value} == 1 && join(',', @$private_array) eq '1',
    'rejected private referents remain unchanged');

my $shared_scalar :shared = 9;
$hash{scalar} = \$shared_scalar;
ok(is_shared($hash{scalar}) && ${$hash{scalar}} == 9,
    'references to shared scalar storage remain valid');

my $private_scalar = 11;
ok(rejected(sub { $hash{private_scalar} = \$private_scalar })
        && !defined($hash{private_scalar}),
    'references to private scalar storage are rejected');

ok(!(grep { ref($_) && !is_shared($_) } values %hash),
    'failed writes leave no private references in shared hash storage');
