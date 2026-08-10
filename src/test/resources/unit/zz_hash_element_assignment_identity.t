use strict;
use warnings;
use Test::More tests => 2;

my %hash = (slot => 'first');
my $slot_ref = \$hash{slot};

$hash{slot} = 'second';
is($$slot_ref, 'second',
    'assignment updates an existing hash scalar slot in place');

my $replacement = sub { 'replacement' };
$hash{slot} = $replacement;
is($$slot_ref, $replacement,
    'a reference to a hash slot sees a replacement code reference');
