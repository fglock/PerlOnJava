use strict;
use warnings;
use Test::More tests => 2;

{
    package RequireTiedIncFetchOnce;
    sub TIESCALAR { bless { fetches => 0, value => undef }, $_[0] }
    sub FETCH { ++$_[0]->{fetches}; $_[0]->{value} }
}

{
    SKIP: {
    skip 'the host Perl predates the tied-@INC single-FETCH semantics', 1
        if $] < 5.038;
    local @INC = undef;
    my $tie = tie $INC[0], 'RequireTiedIncFetchOnce';
    my $missing = 'RequireTiedIncFetchOnce::Missing';
    eval { require $missing };
    is($tie->{fetches}, 1, 'require fetches a tied scalar @INC entry once');
    }
}

{
    my $module = 'RequireTiedIncFetchOnce::DefinitelyMissing';
    eval { require $module };
    like($@, qr/Can't locate .* in \@INC/,
        'a missing module still reports the normal @INC search failure');
}
