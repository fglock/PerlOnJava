use strict;
use warnings;
use Test::More;
use re qw(regexp_pattern);

sub modifiers {
    return (regexp_pattern($_[0]))[1];
}

{
    use re '/xx';
    is(modifiers(qr/foo/), 'xx', '/xx preserves both extended levels');
    {
        is(modifiers(qr/foo/), 'xx', 'nested scope inherits both levels');
        use re '/x';
        is(modifiers(qr/foo/), 'xx', 'enabling /x again is idempotent');
        no re '/x';
        is(modifiers(qr/foo/), 'x', 'no re /x downgrades /xx to /x');
    }
    is(modifiers(qr/foo/), 'xx', 'scope exit restores both outer levels');
    {
        no re '/xx';
        is(modifiers(qr/foo/), '', 'no re /xx clears both levels');
    }
}

{
    use re '/i';
    use re '/i';
    is(modifiers(qr/foo/), 'i', 'ordinary modifiers stay duplicate-idempotent');
}

done_testing;
