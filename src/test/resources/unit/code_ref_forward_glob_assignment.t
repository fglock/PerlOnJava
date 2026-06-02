use Test::More tests => 2;

my $callback = \&later;
*later = sub { 42 };

is($callback->(), 42, 'coderef to an undefined glob sees later CODE assignment');

my $eval_callback = eval q{
    my $tmp = \&later_eval;
    *later_eval = sub { 84 };
    $tmp;
};
die $@ if $@;

is($eval_callback->(), 84, 'eval coderef to an undefined glob sees later CODE assignment');
