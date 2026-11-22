use strict;
use warnings;
use Test::More;

BEGIN {
    package Test::Autobox::ScalarReference;
    sub value { ${$_[0]} }
}

{
    use autobox SCALAR => 'Test::Autobox::ScalarReference';
    my $value = 'through a scalar reference';
    my $reference = \$value;
    is($reference->value, 'through a scalar reference',
        'SCALAR mappings dispatch on unblessed scalar references');
}

done_testing();
