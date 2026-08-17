use strict;
use warnings;
use Test::More tests => 13;

{
    package Local::QrOverload;
    use overload 'qr' => sub { qr/(??{50})/ };
}

{
    package Local::StringOverload;
    use overload '""' => sub { qr/(??{51})/ };
}

{
    package Local::QrAndConcatOverload;
    use overload
        'qr' => sub { qr/(??{50})/ },
        '.'  => sub { $_[1] . qr/(??{60})/ };
}

{
    package Local::ConcatOverload;
    use overload '.' => sub { $_[1] . qr/(??{52})/ };
}

{
    package Local::StringAndConcatOverload;
    use overload
        '""' => sub { qr/(??{7})/ },
        '.'  => sub { $_[1] . qr/(??{53})/ };
}

{
    package Local::IndirectStringOverload;
    use overload '""' => sub { $_[0][0] };
}

package main;

my $qr_object = bless [], 'Local::QrOverload';
ok('A50' =~ /^A$qr_object$/, 'embedded qr overload retains executable provenance');
ok('50' =~ /$qr_object/, 'bare qr overload retains executable provenance');

my $string_object = bless [], 'Local::StringOverload';
ok('A51' =~ /^A$string_object$/, 'embedded string overload returning qr retains provenance');
ok('51' =~ /$string_object/, 'bare string overload returning qr retains provenance');

my $both_object = bless [], 'Local::QrAndConcatOverload';
ok('A50' =~ /^A$both_object$/, 'embedded interpolation prefers qr overload to concat overload');

my $concat_object = bless [], 'Local::ConcatOverload';
my $concat_error = eval { 'A52' =~ /^A$concat_object$/; 1 } ? '' : $@;
like($concat_error, qr/Eval-group not allowed/, 'concat overload source remains runtime source');
my $bare_concat_error = eval { '52' =~ /$concat_object/; 1 } ? '' : $@;
like($bare_concat_error, qr/no method found/, 'bare interpolation does not use concat overload');
{
    use re 'eval';
    ok('A52' =~ /^A$concat_object$/, 'use re eval permits concat overload source');
}

my $string_concat_object = bless [], 'Local::StringAndConcatOverload';
my $string_concat_error = eval { 'A53' =~ /^A$string_concat_object$/; 1 } ? '' : $@;
like($string_concat_error, qr/Eval-group not allowed/, 'concat overload precedes string overload');
my $bare_string_error = eval { '7' =~ /$string_concat_object/; 1 } ? '' : $@;
is($bare_string_error, '', 'bare interpolation uses string overload without concat');
{
    use re 'eval';
    ok('A53' =~ /^A$string_concat_object$/, 'use re eval permits preferred concat overload source');
}

my $indirect_object = bless [ $string_object ], 'Local::IndirectStringOverload';
ok('A51' =~ /^A$indirect_object/, 'indirect string overload retains executable provenance');
ok('51' =~ /$indirect_object/, 'bare indirect string overload retains provenance');
