use strict;
use warnings;
use Test::More;
use CPAN::Distroprefs;

my $pref = CPAN::Distroprefs::Pref->new({
    data => {
        match => {
            distribution => '^SKIM/Error-Pure-[0-9]',
        },
        patches => [ 'Error-Pure-0.34/PlainLexicalConstants.patch' ],
        test => {
            commandline => 'PERLONJAVA_TEST_IGNORE_FAILURES',
        },
    },
});

my %match_info = (
    distribution => 'SKIM/Error-Pure-0.34.tar.gz',
    module       => [],
    perl         => $^X,
    perlconfig   => {},
    env          => {},
);

ok(
    $pref->matches(\%match_info),
    'Error-Pure distropref matches the main Error-Pure distribution',
);

$match_info{distribution} = 'SKIM/Error-Pure-Output-Text-0.24.tar.gz';
ok(
    !$pref->matches(\%match_info),
    'Error-Pure distropref does not match Error-Pure-Output-Text',
);

done_testing;
