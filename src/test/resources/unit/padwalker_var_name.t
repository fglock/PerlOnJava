use strict;
use warnings;
use Test::More tests => 8;
use PadWalker qw(var_name);

sub check_current_pad {
    my $scalar = 42;
    my @array = qw(alpha beta);
    my %hash = (answer => 42);
    my $unrelated = 'other';

    is(var_name(0, \$scalar), '$scalar', 'finds a scalar in the current pad');
    is(var_name(0, \@array), '@array', 'finds an array in the current pad');
    is(var_name(0, \%hash), '%hash', 'finds a hash in the current pad');
    is(var_name(0, \$unrelated), '$unrelated', 'finds another current-pad variable');
}

sub name_in_caller {
    return var_name(1, shift);
}

sub names_through_map {
    return map { var_name(1, \$_) } @_;
}

sub check_caller_pad {
    my $caller_value = 42;
    is(name_in_caller(\$caller_value), '$caller_value', 'finds a variable in a caller pad');

    my $first = 'one';
    my $second = 'two';
    is_deeply([names_through_map($first, $second)], ['$first', '$second'],
        'map aliases do not add a PadWalker caller level');
}

check_current_pad();
check_caller_pad();

my $captured = 'value';
my $closure = sub { return $captured };
is(var_name($closure, \$captured), '$captured', 'finds a captured variable in a coderef');

my $unrelated = 'other';
ok(!defined var_name($closure, \$unrelated), 'returns undef for an unrelated variable');
