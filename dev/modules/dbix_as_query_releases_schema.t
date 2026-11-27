use strict;
use warnings;

use Test::More;
use Scalar::Util qw(weaken);

use lib $ENV{DBIX_CLASS_LIB}
    // die "Set DBIX_CLASS_LIB to the installed DBIx::Class lib directory\n";
use lib $ENV{DBIX_CLASS_TEST_LIB}
    // die "Set DBIX_CLASS_TEST_LIB to the DBIx::Class t/lib directory\n";
use DBICTest;

my $weak_schema;
{
    my $schema = DBICTest->init_schema();
    weaken($weak_schema = $schema);

    my $rs = $schema->resultset('Artist')->search(
        {},
        {
            join => 'cds',
            distinct => 1,
            '+select' => [ { count => 'cds.cdid', -as => 'amount_of_cds' } ],
            '+as' => [qw/num_cds/],
            order_by => { -desc => 'amount_of_cds' },
        },
    );

    my $query = $rs->as_query;
    ok(ref($query), 'as_query returns the packed query');
}

ok(!defined($weak_schema), 'discarded as_query result releases its schema');
done_testing();
