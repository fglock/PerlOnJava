use strict;
use warnings;
use Test::More tests => 4;

my @cases = (
    {
        name => 'large positive relative call',
        pattern => '((?+2147483647))',
        expected => 'Invalid reference to group in regex; marked by <-- HERE '
            . 'in m/((?+2147483647) <-- HERE )/ at -e line 1.' . "\n",
    },
    {
        name => 'large negative relative call',
        pattern => '((?-2147483647))',
        expected => 'Reference to nonexistent group in regex; marked by <-- HERE '
            . 'in m/((?-2147483647) <-- HERE )/ at -e line 1.' . "\n",
    },
);

for my $case (@cases) {
    my $command = "$^X -e 'qr/$case->{pattern}/' 2>&1";
    my $error = qx{$command};
    isnt($?, 0, "$case->{name} fails during child compilation");
    is($error, $case->{expected}, "$case->{name} retains child source location");
}
