use strict;
use warnings;
use Test::More;

{
    package LocalTiedUnderscore;
    sub TIESCALAR { bless {}, shift }
    sub FETCH { die 'outer tied $_ was fetched' }
    sub STORE { die 'outer tied $_ was stored' }
}

{
    package LocalTiedUnderscore;
    tie $_, __PACKAGE__;
    my $joined = '';
    for (1 .. 3) { $joined .= $_ }
    main::is($joined, '123', 'implicit foreach $_ temporarily strips outer tie magic');
    main::is(eval { my $value = $_; 1 }, undef, 'outer tie is restored after foreach');
    main::like($@, qr/outer tied \$_ was fetched/, 'restored tie receives fetches');
    untie $_;
}

package main;
done_testing;
