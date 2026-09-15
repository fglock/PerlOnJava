use strict;
use warnings;
use Test::More tests => 3;

my $pid = open FROM_CHILD, '-|';
unless (defined $pid) {
    fail('bareword fork-open starts a child');
    fail('parent reads bareword child output');
    fail('bareword child exits successfully');
    exit 0;
}

if ($pid) {
    ok($pid > 0, 'bareword fork-open starts a child');
    is(<FROM_CHILD>, "bareword fork-open output\n",
        'parent reads a bareword child format write');
    ok(close FROM_CHILD, 'bareword child exits successfully');
    exit 0;
}

{
    format STDOUT =
@*
'bareword fork-open output'
.
    write;
    close STDOUT;
    exit 0;
}
