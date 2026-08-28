use strict;
use warnings;
use Test::More;

my $scalar = 'value';
my $object = bless {}, 'Local::Downgrade::Object';
my @references = (
    [ CODE   => sub { 42 } ],
    [ ARRAY  => [] ],
    [ HASH   => {} ],
    [ SCALAR => \$scalar ],
    [ 'Local::Downgrade::Object' => $object ],
);

for my $case (@references) {
    my ($expected_type, $value) = @$case;
    is(utf8::downgrade($value, 1), 1, "downgrade succeeds for $expected_type reference");
    is(ref($value), $expected_type, "downgrade preserves $expected_type reference");
}

is($references[0][1]->(), 42, 'downgrade preserves a callable code reference');

done_testing;
