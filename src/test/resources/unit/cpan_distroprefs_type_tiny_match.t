use strict;
use warnings;
use Test::More;
use CPAN::Distroprefs;

my $pref = CPAN::Distroprefs::Pref->new({
    data => {
        match => { distribution => '^TOBYINK/Type-Tiny-[0-9]' },
        patches => [ 'Type-Tiny/SkipRegexCallbackTests.patch' ],
    },
});

my %match_info = (
    distribution => 'TOBYINK/Type-Tiny-2.010001.tar.gz',
    module       => [],
    perl         => $^X,
    perlconfig   => {},
    env          => {},
);

ok($pref->matches(\%match_info), 'Type-Tiny preference matches Type-Tiny');
$match_info{distribution} = 'TOBYINK/Type-Tiny-XS-0.025.tar.gz';
ok(!$pref->matches(\%match_info),
   'Type-Tiny preference does not match Type-Tiny-XS');

done_testing;
