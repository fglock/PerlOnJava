use strict;
use warnings;
use re ();
use Test::More;

my $exact = re::optimization(qr/abc/);
is(ref($exact), 'HASH', 'optimization returns a hash reference');
is($exact->{minlen}, 3, 'exact minimum length');
is($exact->{minlenret}, 3, 'returned minimum length');
is($exact->{anchored}, 'abc', 'fixed-offset exact string is anchored');
is($exact->{'anchored min offset'}, 0, 'anchored offset');
is($exact->{checking}, 'anchored', 'anchored string is checked');
is($exact->{isall}, 1, 'plain exact pattern is entirely optimized');
is(re::optimization(qr/a()bc/)->{isall}, 0,
    'capture op prevents an all-exact optimization');

my $floating = re::optimization(qr/x?abc/);
is($floating->{minlen}, 3, 'optional prefix does not raise minimum length');
is($floating->{floating}, 'abc', 'variable-offset exact string is floating');
is($floating->{'floating min offset'}, 0, 'floating minimum offset');
is($floating->{'floating max offset'}, 1, 'floating maximum offset');
is($floating->{checking}, 'floating', 'floating string is checked');

my $empty = re::optimization(qr//);
is($empty->{minlen}, 0, 'empty pattern minimum length');
is($empty->{checking}, 'none', 'empty pattern has no exact check');
ok(!defined(re::optimization('abc')), 'non-regex input returns undef');

done_testing;
