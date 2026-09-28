use v5.36;
use feature 'refaliasing';
no warnings 'experimental::refaliasing';

print "1..1\n";

{
    my @a;
    goto do_aliasing;

do_test:
    @a[0, 1] = qw(a b);
    my ($y, $x) = ($a[0], $a[1]);
    print "@a" eq 'b a'
        ? "ok 1 - a refalias list preserves forward lexical cells until their declarations\n"
        : "not ok 1 - a refalias list preserves forward lexical cells until their declarations\n";
    last;

do_aliasing:
    \(@a) = \($x, $y);
    goto do_test;
}
