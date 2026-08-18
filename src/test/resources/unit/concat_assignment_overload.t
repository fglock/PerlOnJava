use strict;
use warnings;
use Test::More;

{
    package StringOnly;
    use overload '""' => sub { $_[0]->[0] };
}

my $rhs = bless ['R'], 'StringOnly';
my $plain = 'L';
my $ok = eval { $plain .= $rhs; 1 };
ok($ok, 'plain string lhs accepts stringify-only overloaded rhs');
is($plain, 'LR', 'rhs stringification supplies concat-assignment value');
is(ref($plain), '', 'ordinary concat assignment leaves a plain scalar');

my $lhs = bless ['L'], 'StringOnly';
$ok = eval { $lhs .= 'R'; 1 };
ok($ok, 'stringify-only overloaded lhs accepts concat assignment');
is($lhs, 'LR', 'lhs is stringified before concat assignment');
is(ref($lhs), '', 'concat assignment replaces stringify-only lhs object');

{
    package ExplicitConcat;
    use overload
        '.' => sub {
            my ($left, $right, $reversed) = @_;
            return $reversed ? "R:$right" : "L:$right";
        },
        '""' => sub { 'OBJECT' };
}

my $explicit = bless [], 'ExplicitConcat';
$ok = eval { $explicit .= 'tail'; 1 };
ok($ok, 'explicit concat overload remains eligible for concat assignment');
is($explicit, 'L:tail', 'explicit concat overload result is assigned to lhs');

done_testing;
