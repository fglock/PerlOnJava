use strict;
use warnings;
use utf8;
use Test::More;

my $supports_named_signatures = eval q{
    use feature 'signatures';
    no warnings 'experimental::signature_named_parameters';
    sub _named_validation_probe (:$required, @rest) { 1 }
    1;
};

SKIP: {
    skip 'Perl does not support named signature parameters', 4
        unless $supports_named_signatures;

    eval q{
        use feature 'signatures';
        no warnings 'experimental::signature_named_parameters';
        sub _named_validation_unicode (:$ĉevaloj) { $ĉevaloj }
        sub _named_validation_slurpy (:$required, @rest) { 'ok' }
    };
    ok(!$@, 'named signature regression declarations compile');

    my $unknown = eval { _named_validation_unicode('ŝafoj' => 5); 1 };
    ok(!$unknown, 'unknown named key is rejected');
    like($@, qr/^Unrecognized named parameter 'ŝafoj'/,
        'unknown named key is diagnosed before a missing required key');

    is(_named_validation_slurpy('one positional value'), 'ok',
        'native @ slurpy accepts an odd trailing positional argument');
}

done_testing;
