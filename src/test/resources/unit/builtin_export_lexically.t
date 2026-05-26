use strict;
use warnings;
use Test::More;

BEGIN {
    package LexicalExportSource;
    $INC{'LexicalExportSource.pm'} = __FILE__;
    our $bar = 42;
    our @items = qw(a b);
    our %lookup = (answer => 42);
    sub foo { return $bar }
    sub import {
        no warnings 'experimental::builtin';
        builtin::export_lexically(
            foo => \&foo,
            '$bar' => \$bar,
            '@items' => \@items,
            '%lookup' => \%lookup,
        );
    }
}

{
    use LexicalExportSource;

    is(foo(), 42, 'lexically imported sub is callable');
    is($bar, 42, 'lexically imported scalar is visible');
    is_deeply(\@items, [qw(a b)], 'lexically imported array is visible');
    is($lookup{answer}, 42, 'lexically imported hash is visible');
    ok(!main->can('foo'), 'lexically imported sub is not installed in caller stash');
}

ok(!eval 'foo()');
ok(!eval '$bar');

done_testing;
