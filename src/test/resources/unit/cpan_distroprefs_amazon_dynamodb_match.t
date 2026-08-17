use strict;
use warnings;
use Test::More;
use CPAN::Distroprefs;

my $pref = CPAN::Distroprefs::Pref->new({
    data => {
        match => {
            distribution => '^.*/Amazon-DynamoDB-0\\.25\\.tar\\.gz$',
        },
        patches => [ 'Amazon-DynamoDB/LazyLoadApiVersion.patch' ],
    },
});

my %match_info = (
    distribution => 'RCONOVER/Amazon-DynamoDB-0.25.tar.gz',
    module       => [],
    perl         => $^X,
    perlconfig   => {},
    env          => {},
);

ok(
    $pref->matches(\%match_info),
    'Amazon-DynamoDB distropref matches release 0.25',
);

$match_info{distribution} = 'RCONOVER/Amazon-DynamoDB-0.26.tar.gz';
ok(
    !$pref->matches(\%match_info),
    'Amazon-DynamoDB distropref is version-scoped',
);

done_testing;
