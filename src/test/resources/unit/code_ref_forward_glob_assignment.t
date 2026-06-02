use Test::More tests => 3;

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

{
    no strict 'refs';
    my $name = 'Restored::missing';
    my $saved = \&{$name};
    *{$name} = sub { 1 };
    *{$name} = $saved;
    my $ok = eval { Restored::missing(); 1 };
    like($@, qr/Undefined subroutine &Restored::missing called/, 'restoring a symbolic undefined coderef keeps the slot undefined');
}
