use strict;
use warnings;
use Scalar::Util qw(weaken);
use Test::More tests => 6;

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
    my $slot = \$items[0];
    return \$slot;
}

sub escape_hash_element_cell_value {
    my %items = (value => $_[0]);
    my $slot = \$items{value};
    return \$slot;
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

done_testing;
