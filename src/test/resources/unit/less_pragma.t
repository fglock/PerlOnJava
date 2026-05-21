use strict;
use warnings;
use Test::More;

ok(!less->of, 'less has no active tags by default');

{
    use less;
    ok(less->of('please'), 'use less defaults to please');
    is_deeply([sort less->of], ['please'], 'less->of lists active default tag');
}

ok(!less->of('please'), 'less hint is lexical');

{
    use less qw(memory CPU);
    ok(less->of('memory'), 'less sees memory tag');
    ok(less->of('CPU'), 'less sees CPU tag');
    ok(less->of('CPU', 'disk'), 'less checks any requested tag');

    {
        no less 'CPU';
        ok(less->of('memory'), 'no less removes only named tag');
        ok(!less->of('CPU'), 'CPU tag removed');
    }

    ok(less->of('CPU'), 'outer less tags are restored');
}

done_testing;
