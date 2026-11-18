use strict;
use warnings;

our @observed_dbi_handle_blesses;

BEGIN {
    *CORE::GLOBAL::bless = sub {
        my $class = @_ > 1 ? $_[1] : caller();
        push @observed_dbi_handle_blesses, $class
            if $class =~ /^DBI::(?:dr|db|st)$/;
        return CORE::bless($_[0], @_ > 1 ? $_[1] : caller());
    };
}

use Test::More tests => 4;
use DBI;
use Scalar::Util qw(weaken);

my $weak_driver;
{
    my $driver = DBI->install_driver('SQLite');
    ok($driver, 'DBI installs the SQLite driver');
    is(ref($driver), 'DBI::dr', 'driver handle has the DBI::dr outer class');
    $weak_driver = $driver;
    weaken($weak_driver);
}

ok(defined $weak_driver, 'DBI keeps its installed driver handle alive');
is_deeply(\@observed_dbi_handle_blesses, [],
    'internal DBI handle construction bypasses CORE::GLOBAL::bless');
