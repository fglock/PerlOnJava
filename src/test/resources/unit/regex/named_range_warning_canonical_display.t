use strict;
use warnings;
use Test::More tests => 12;

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
    is(scalar @warnings, 1, "$source emits one warning");
    return $warnings[0];
}

for my $case (
    [q{[\x00-\N{SOH}]\x{100}}, q{\N{U+01}}, q{\N{SOH}}],
    [q{[\N{DEL}-\o{377}]\x{100}}, q{\N{U+7F}}, q{\N{DEL}}],
    [q{[\N{DEL}-\377]\x{100}}, q{\N{U+7F}}, q{\N{DEL}}],
) {
    my ($source, $canonical, $original) = @$case;
    my $warning = compile_warnings($source);
    like($warning,
         qr/Both or neither range ends should be Unicode.*\Q$canonical\E.*<-- HERE/s,
         "$source warning uses the canonical named endpoint");
    unlike($warning, qr/\Q$original\E/,
           "$source warning omits the source alias");
}
