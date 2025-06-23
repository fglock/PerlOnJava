use feature 'say';
use strict;
use Test::More;

###################
# Perl V-String Operations Tests

subtest 'V-String basic operations' => sub {
    # V-String creation and assignment
    my $vstring = v1.2.3;
    is($vstring, "\x01\x02\x03", 'V-String creation and assignment');

    my $ref = ref(\$vstring);
    is($ref, 'VSTRING', 'V-String ref');

    # V-String with leading zero
    $vstring = v0.10.20;
    is($vstring, "\x00\x0A\x14", 'V-String with leading zero');
};

subtest 'V-String comparison and concatenation' => sub {
    # V-String comparison
    my $vstring1 = v1.2.3;
    my $vstring2 = v1.2.4;
    ok(!($vstring1 ge $vstring2), 'V-String comparison');

    # V-String concatenation
    my $concat_vstring = $vstring1 . $vstring2;
    is($concat_vstring, "\x01\x02\x03\x01\x02\x04", 'V-String concatenation');
};

subtest 'V-String length and conversion' => sub {
    my $vstring1 = v1.2.3;

    # V-String length
    my $length = length $vstring1;
    is($length, 3, 'V-String length');

    # V-String to number conversion
    my $number = ord(substr($vstring1, 0, 1));
    is($number, 1, 'V-String to number conversion');
};

subtest 'V-String in collections' => sub {
    # V-String in array context
    my @vstring_array = (v1.2.3, v4.5.6);
    is($vstring_array[0], "\x01\x02\x03", 'V-String in array context - first element');
    is($vstring_array[1], "\x04\x05\x06", 'V-String in array context - second element');
};

subtest 'V-String split and join' => sub {
    my $vstring1 = v1.2.3;

    # V-String split operation
    my @split_vstring = split //, $vstring1;
    is(scalar @split_vstring, 3, 'V-String split - correct number of elements');
    is($split_vstring[0], "\x01", 'V-String split - first element');
    is($split_vstring[2], "\x03", 'V-String split - third element');

    # V-String join operation
    my $joined_vstring = join '', @split_vstring;
    is($joined_vstring, $vstring1, 'V-String join operation');
};

subtest 'V-String references' => sub {
    # V-String reference creation
    my $vstring = v1.2.3;
    my $vstring_ref = \$vstring;
    is($$vstring_ref, "\x01\x02\x03", 'V-String reference creation');

    # Modify v-string through reference
    $$vstring_ref = v4.5.6;
    is($vstring, "\x04\x05\x06", 'Modify v-string through reference');
};

done_testing();
