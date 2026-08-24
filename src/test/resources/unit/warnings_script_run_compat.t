use strict;
use warnings;
use Test::More;

my $ok = eval q{
    no warnings 'experimental::script_run';
    1;
};
ok($ok, 'historical experimental::script_run category remains accepted');
is($@, '', 'historical script-run warning category is a no-op');

done_testing;
