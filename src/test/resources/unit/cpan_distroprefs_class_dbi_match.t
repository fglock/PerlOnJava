use strict;
use warnings;
use Test::More;
use CPAN::Distroprefs;

my $pref = CPAN::Distroprefs::Pref->new({
    data => {
        match => {
            distribution => '^.*/Class-DBI-v?[0-9]',
        },
        patches => [ 'Class-DBI/Class-DBI.pm.patch' ],
    },
});

my %match_info = (
    distribution => 'TMTM/Class-DBI-v3.0.17.tar.gz',
    module       => [],
    perl         => $^X,
    perlconfig   => {},
    env          => {},
);

ok(
    $pref->matches(\%match_info),
    'Class-DBI distropref matches the main Class-DBI distribution',
);

$match_info{distribution} = 'TMTM/Class-DBI-Search-Count-1.00.tar.gz';
ok(
    !$pref->matches(\%match_info),
    'Class-DBI distropref does not match Class-DBI-Search-Count',
);

done_testing;
