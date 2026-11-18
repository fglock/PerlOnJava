use strict;
use warnings;
use Scalar::Util qw(weaken);
use Test::More tests => 3;

my ($weak_glob, $stash);
{
    no strict 'refs';
    my $package = 'PackageIOGlobOwnerLifetime';
    $stash = \%{"${package}::"};
    my $glob = \*{"${package}::FH"};
    $weak_glob = $glob;
    weaken($weak_glob);
}

ok(defined $weak_glob, 'package glob remains live while its stash slot exists');
delete $stash->{FH};
ok(!exists $stash->{FH}, 'stash slot is removed');
is($weak_glob, undef, 'weak glob clears after its final stash slot is removed');

done_testing;
