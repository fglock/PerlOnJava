use strict;
use warnings;
use feature 'state';
use Scalar::Util qw(weaken);
use Test::More;
sub localized_metadata { state $metadata = { value => 10 }; $metadata }
my $original = localized_metadata(); weaken($original);
my ($temporary, $nested);
{
    local *localized_metadata = do {
        my $seed = 20;
        sub { state $metadata = { value => $seed }; $metadata }
    };
    $temporary = localized_metadata(); weaken($temporary);
    ok(defined $original, 'saved CODE slot preserves original state');
    is(localized_metadata()->{value}, 20, 'localized CODE owns independent state');
    {
        local *localized_metadata = do {
            my $seed = 30;
            sub { state $metadata = { value => $seed }; $metadata }
        };
        $nested = localized_metadata(); weaken($nested);
        ok(defined $temporary, 'nested localization preserves outer temporary state');
        is(localized_metadata()->{value}, 30, 'nested CODE owns independent state');
    }
    ok(!defined $nested, 'restoring outer slot releases nested CODE state');
    is(localized_metadata()->{value}, 20, 'outer localized CODE is restored');
}
ok(!defined $temporary, 'restoring original slot releases temporary CODE state');
is(localized_metadata()->{value}, 10, 'original CODE state is restored');
undef *localized_metadata;
ok(!defined $original, 'removing final original slot releases original state');
done_testing();
