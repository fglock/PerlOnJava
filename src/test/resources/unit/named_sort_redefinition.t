use strict;
use warnings;
use Test::More tests => 6;

our ($a, $b);

{
    no warnings 'redefine';
    *twoface = sub { $b <=> $a };
}
my @sorted = sort twoface 4, 1, 3, 2;
is "@sorted", '4 3 2 1', 'a redefinition before sort is visible';

{
    no warnings 'redefine';
    *twoface = sub { *twoface = sub { $b <=> $a }; $a <=> $b };
}
@sorted = sort twoface 4, 1, 9, 5;
is "@sorted", '1 4 5 9', 'redefinition does not affect the active sort';

{
    no warnings 'redefine';
    *twoface = sub {
        eval 'sub twoface { $a <=> $b }';
        die($@ eq '' ? "good\n" : "bad\n");
    };
}
eval { @sorted = sort twoface 4, 1 };
is substr($@, 0, 4), 'good', 'eval may redefine the active named comparator';

{
    no warnings 'redefine';
    sub a { 0 }
    my @values = sort { *a = sub { 1 }; $a <=> $b } 0 .. 1;
    ok a(), 'sort scalar localization does not restore the code slot';
}

{
    no warnings 'redefine';
    sub replaceable { 'old' }
    my $saved = \&replaceable;
    *replaceable = sub { 'new' };
    is replaceable(), 'new', 'a direct call follows the current glob CODE slot';
    is $saved->(), 'old', 'a saved code reference keeps the old CV';
}
