use strict;
use warnings;
use Test::More;

BEGIN {
    # Use the installed CPAN module rather than the small Catalyst test fixture
    # in unit/lib, which does not provide the complete MOP implementation.
    @INC = grep {
        ref($_) || $_ !~ m{(?:^|[/\\])src/test/resources/unit/lib/?\z}
    } @INC;

    my $available = eval {
        require Catalyst::Stats;
        require Tree::Simple;
        1;
    };
    plan skip_all => 'Catalyst::Stats test dependencies are not installed' unless $available;
}

my $stats;
my $error;
eval {
    $stats = Catalyst::Stats->new;
    1;
} or $error = $@;

ok($stats, 'Catalyst::Stats can call its imported clock CV from a stored default');
diag($error) unless $stats;
done_testing;
