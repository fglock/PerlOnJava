use strict;
use warnings;
use Scalar::Util qw(refaddr);
use Test::More tests => 6;

my %defaults = (value => 1);

sub make_inside_out_object {
    my ($class) = @_;
    my $object = bless \my($anonymous_scalar), $class;
    my $value = eval '$defaults{value}';
    die $@ if $@;
    return ($object, $value);
}

my ($first, $first_value) = make_inside_out_object('First');
my ($second, $second_value) = make_inside_out_object('Second');

is $first_value, 1, 'runtime eval sees the enclosing file lexical';
is $second_value, 1, 'runtime eval keeps working on later calls';
isnt refaddr($first), refaddr($second), 'referenced my scalar is fresh on each call';
is ref($first), 'First', 'first object keeps its original class';
is ref($second), 'Second', 'second object receives its requested class';

$defaults{value} = 2;
my (undef, $updated_value) = make_inside_out_object('Third');
is $updated_value, 2, 'runtime eval observes updates without persistent local cells';
