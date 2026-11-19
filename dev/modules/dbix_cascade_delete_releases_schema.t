use strict;
use warnings;

use Test::More;

use lib ($ENV{DBIX_CLASS_LIB}
    // die "Set DBIX_CLASS_LIB to the installed DBIx::Class lib directory\n"), 't/lib';
use DBICTest;

my $schema = DBICTest->init_schema();
$schema->populate('Artist', [
    [qw/name cds/],
    ['Cascade cleanup artist', [
        { title => 'Cascade cleanup CD', year => 2026 },
    ]],
]);

$schema->resultset('Artist')->find({
    name => 'Cascade cleanup artist',
})->delete;

ok(1, 'populate and cascading delete completed');

done_testing();
