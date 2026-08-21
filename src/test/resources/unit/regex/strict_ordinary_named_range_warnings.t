use strict;
use warnings;
use Test::More tests => 24;

sub compile_warnings {
    my ($source) = @_;
    my @warnings;
    my $compiled;
    {
        local $SIG{__WARN__} = sub { push @warnings, @_ };
        $compiled = eval "no warnings 'experimental::re_strict'; "
                       . "use re 'strict'; qr/$source/";
    }
    ok(defined $compiled, "$source compiles under re strict");
    return @warnings;
}

for my $source (
    q{[\N{U+00}-\x01]},
    q{[\x00-\N{SOH}]},
    q{[\N{DEL}-\o{377}]},
    q{[\o{0}-\N{U+01}]},
    q{[\000-\N{U+01}]},
    q{[\N{DEL}-\377]},
) {
    my @warnings = compile_warnings($source);
    is(scalar @warnings, 1, "$source emits one range-end warning");
    like($warnings[0],
         qr/Both or neither range ends should be Unicode.*<-- HERE/s,
         "$source preserves named-character endpoint provenance");
}

for my $source (
    q{[\N{U+00}-\a]},
    q{[\a-\N{U+FF}]},
    q{[\N{U+100}-\x{101}]},
) {
    my @warnings = compile_warnings($source);
    is(scalar @warnings, 0,
       "$source has two Unicode-equivalent range endpoints");
}
