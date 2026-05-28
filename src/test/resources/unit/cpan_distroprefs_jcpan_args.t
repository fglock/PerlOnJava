use strict;
use warnings;
use Test::More;
use CPAN::Distroprefs;

my $pref = CPAN::Distroprefs::Pref->new({
    data => {
        match => {
            distribution => '^OALDERS/libwww-perl-',
            env => {
                not_PERLONJAVA_JCPAN_ARGS =>
                    '(^|[[:space:]])(?:LWP|LWP::UserAgent)($|[[:space:]])',
            },
        },
        test => {
            commandline => 'PERLONJAVA_SKIP',
        },
    },
});

my %match_info = (
    distribution => 'OALDERS/libwww-perl-6.83.tar.gz',
    module       => [],
    perl         => $^X,
    perlconfig   => {},
    env          => {},
);

ok(
    $pref->matches(\%match_info),
    'libwww-perl dependency skip matches when no direct jcpan args are exposed',
);

$match_info{env}{PERLONJAVA_JCPAN_ARGS} = '-t LWP::Online';
ok(
    $pref->matches(\%match_info),
    'libwww-perl dependency skip still matches for a downstream target',
);

$match_info{env}{PERLONJAVA_JCPAN_ARGS} = '-t LWP';
ok(
    !$pref->matches(\%match_info),
    'libwww-perl dependency skip does not match direct LWP tests',
);

$match_info{env}{PERLONJAVA_JCPAN_ARGS} = '-t LWP::UserAgent';
ok(
    !$pref->matches(\%match_info),
    'libwww-perl dependency skip does not match direct LWP::UserAgent tests',
);

done_testing;
