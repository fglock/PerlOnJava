use strict;
use warnings;
use Test::More tests => 14;

{
    package RuntimeControlVerbInactive;
    our $REGERROR = 'inactive error';
    our $REGMARK = 'inactive mark';
}

{
    package RuntimeControlVerbPackage;
    our $REGERROR;
    local $REGERROR;

    for my $name ('', ':named') {
        for my $verb ('PRUNE', 'SKIP', 'COMMIT') {
            my $mark = $verb eq 'SKIP' && $name ? "(*MARK$name)" : '';
            my $pattern = "$mark(*$verb$name)(*FAIL)";
            'aaaab' =~ /a+b$pattern/;
            ::is($REGERROR, $name ? 'named' : '1',
                "runtime $verb failure updates package REGERROR");

            $pattern = "$mark(*$verb$name)";
            'aaaab' =~ /a+b$pattern/;
            ::is($REGERROR, '',
                "runtime $verb success clears package REGERROR");
        }
    }
}

is($RuntimeControlVerbInactive::REGERROR, 'inactive error',
    'runtime control verbs do not update non-localized REGERROR variables');
is($RuntimeControlVerbInactive::REGMARK, 'inactive mark',
    'runtime control verbs do not update non-localized REGMARK variables');
