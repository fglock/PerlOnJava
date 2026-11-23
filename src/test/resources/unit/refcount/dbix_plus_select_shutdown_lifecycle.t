use strict;
use warnings;

BEGIN {
    my $root = $ENV{DBIC_CLASS_TEST_ROOT};
    if (!defined($root) || !-d "$root/t/lib" || !-d "$root/lib") {
        print "1..0 # SKIP set DBIC_CLASS_TEST_ROOT to a DBIx::Class checkout\n";
        exit 0;
    }
    $ENV{DBICTEST_NO_MAKEFILE_VERIFICATION} ||= 1;
    $ENV{AUTOMATED_TESTING} ||= 1;
    unshift @INC, "$root/t/lib", "$root/lib";
    require DBICTest;
}

use Test::More;

my $schema = DBICTest->init_schema();
my $cd_rs = $schema->resultset('CD')->search(
    { genreid => { '!=', undef } },
    { order_by => 'cdid' },
);
my $track_count = $cd_rs->search({}, { rows => 1 })
    ->search_related('tracks')->count;
my %base_columns = $cd_rs->first->get_columns;
my $plus_rs = $cd_rs->search(
    {},
    {
        join => 'tracks',
        distinct => 1,
        '+select' => { count => 'tracks.trackid' },
        '+as' => 'tr_cnt',
    },
);

is_deeply(
    { $plus_rs->first->get_columns },
    { %base_columns, tr_cnt => $track_count },
    'plus-select returns the expected extra column',
);
my %relationship_override = (artist => $base_columns{artist});
is_deeply(
    { $plus_rs->first->get_inflated_columns, %relationship_override },
    { %base_columns, tr_cnt => $track_count },
    'plus-select inflated columns retain the expected values',
);

done_testing();
