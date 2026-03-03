use strict;
use warnings;
use Test::More;

# $& survives statement modifier while
{
    my $c = 2;
    my $match;
    my $subject = "abc";
    $match = ($subject =~ m/abc/) while $c--;
    is($&, "abc", '$& survives statement modifier while');
}

# $1 survives statement modifier while
{
    my $c = 2;
    "hello" =~ /(hello)/ while $c--;
    is($1, "hello", '$1 survives statement modifier while');
}

# @-/@+ survive statement modifier while
{
    my $c = 2;
    my $match;
    my $subject = "xabcy";
    $match = ($subject =~ m/abc/) while $c--;
    is($-[0], 1, '$-[0] survives statement modifier while');
    is($+[0], 4, '$+[0] survives statement modifier while');
}

# $& survives statement modifier for
{
    "abc" =~ m/abc/;
    my $x;
    $x++ for 1..3;
    is($&, "abc", '$& survives statement modifier for (no regex in body)');
}

# $1 restored after block for(;;) loop
{
    "abc" =~ /(a)/;
    is($1, 'a', '$1 set before for(;;) loop');
    for (my $i = 0; $i < 2; $i++) {
        "x${i}y" =~ /(\d)/;
    }
    is($1, 'a', '$1 restored after block for(;;) loop');
}

# $1 restored after block foreach loop
{
    "abc" =~ /(a)/;
    foreach my $i (1..2) {
        "x${i}y" =~ /(\d)/;
    }
    is($1, 'a', '$1 restored after block foreach loop');
}

# $1 persists across iterations inside for(;;)
{
    my $s = "a1b2c3";
    my @seen;
    for (;;) {
        last unless $s =~ /([a-z])/g;
        push @seen, $1;
        $s =~ /(\d)/g or last;
    }
    is_deeply(\@seen, ['a', 'b', 'c'], '$1 persists across for(;;) iterations');
}

# $1 not clobbered by statement modifier while with regex
{
    "outer" =~ /(outer)/;
    my $c = 2;
    "inner" =~ /(inner)/ while $c--;
    is($1, 'inner', '$1 from statement modifier while is visible (no block scope)');
}

done_testing();
