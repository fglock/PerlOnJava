use strict;
use warnings;
use Scalar::Util qw(weaken);
use Test::More tests => 10;

our ($scalar_cell_destroyed, $array_cell_destroyed, $hash_cell_destroyed) = (0, 0, 0);

{
    package Issue1649::ScalarCellValueOwner;
    sub DESTROY { $main::scalar_cell_destroyed++ }

    package Issue1649::ArrayCellValueOwner;
    sub DESTROY { $main::array_cell_destroyed++ }

    package Issue1649::HashCellValueOwner;
    sub DESTROY { $main::hash_cell_destroyed++ }
}

sub escape_scalar_cell_value {
    my $slot = $_[0];
    return \$slot;
}

sub escape_array_element_cell_value {
    my @items = ($_[0]);
    return \$items[0];
}

sub escape_hash_element_cell_value {
    my %items = (value => $_[0]);
    return \$items{value};
}

sub escape_array_and_element_cell {
    my @items = ($_[0]);
    return (\@items, \$items[0]);
}

sub escape_hash_and_element_cell {
    my %items = (value => $_[0]);
    return (\%items, \$items{value});
}

my $scalar_object = bless {}, 'Issue1649::ScalarCellValueOwner';
my $weak_scalar_object = $scalar_object;
weaken($weak_scalar_object);
my $escaped_scalar_cell = escape_scalar_cell_value($scalar_object);
undef $scalar_object;
ok(defined $weak_scalar_object,
    'escaped scalar cell keeps its value alive after lexical scope exit');
undef $escaped_scalar_cell;
ok(!defined($weak_scalar_object) && $scalar_cell_destroyed == 1,
    'escaped scalar cell releases its value after the final alias leaves');

my $array_object = bless {}, 'Issue1649::ArrayCellValueOwner';
my $weak_array_object = $array_object;
weaken($weak_array_object);
my $escaped_array_cell = escape_array_element_cell_value($array_object);
undef $array_object;
ok(defined $weak_array_object,
    'escaped array element cell keeps its value alive after array scope exit');
undef $escaped_array_cell;
ok(!defined($weak_array_object) && $array_cell_destroyed == 1,
    'escaped array element cell releases its value after the final alias leaves');

my $hash_object = bless {}, 'Issue1649::HashCellValueOwner';
my $weak_hash_object = $hash_object;
weaken($weak_hash_object);
my $escaped_hash_cell = escape_hash_element_cell_value($hash_object);
undef $hash_object;
ok(defined $weak_hash_object,
    'escaped hash element cell keeps its value alive after hash scope exit');
undef $escaped_hash_cell;
ok(!defined($weak_hash_object) && $hash_cell_destroyed == 1,
    'escaped hash element cell releases its value after the final alias leaves');

my $shared_array_object = bless {}, 'Issue1649::ArrayCellValueOwner';
my $weak_shared_array_object = $shared_array_object;
weaken($weak_shared_array_object);
my ($held_array, $held_element_cell) = escape_array_and_element_cell($shared_array_object);
undef $shared_array_object;
undef $held_element_cell;
ok(defined $weak_shared_array_object,
    'dropping the escaped array cell leaves the live array element intact');
undef $held_array;
ok(!defined($weak_shared_array_object) && $array_cell_destroyed == 2,
    'dropping the final array owner releases the element value');

my $shared_hash_object = bless {}, 'Issue1649::HashCellValueOwner';
my $weak_shared_hash_object = $shared_hash_object;
weaken($weak_shared_hash_object);
my ($held_hash, $held_hash_cell) = escape_hash_and_element_cell($shared_hash_object);
undef $shared_hash_object;
undef $held_hash_cell;
ok(defined $weak_shared_hash_object,
    'dropping the escaped hash cell leaves the live hash element intact');
undef $held_hash;
ok(!defined($weak_shared_hash_object) && $hash_cell_destroyed == 2,
    'dropping the final hash owner releases the element value');

done_testing;
