use strict;
use warnings;
use Test::More;

sub compile_source {
    my ($source) = @_;
    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    my $regex = eval $source;
    return ($regex, $@, \@warnings);
}

my @cases = (
    [q{qr/# %{day_name}/}, 1, 'ordinary mode treats hash as literal'],
    [q{qr/# %{day_name}/x}, 0, '/x ignores brace inside line comment'],
    [qq{qr/(?x:# %{day_name}\n)A/}, 0,
        'scoped /x ignores brace inside line comment'],
    [qq{qr/(?x)# %{day_name}\nA/}, 0,
        'persistent inline /x ignores brace inside line comment'],
    [q{qr/# %{day_name}/xx}, 0,
        '/xx ignores brace inside line comment'],
    [q{qr/(?# %{day_name})A/}, 0, 'comment group ignores brace'],
    [q{qr/\# %{day_name}/x}, 1, 'escaped hash does not start /x comment'],
    [q{qr/[#%{day_name}]/x}, 0, 'character class brace remains quiet'],
    [qq{qr/# %{comment}\n%{actual}/x}, 1,
        'newline ends /x comment before real brace'],
    [q{qr/%{day_name}/x}, 1, 'actual unescaped brace still warns under /x'],
    [q!qr/%\{day_name\}/x!, 0, 'escaped executable brace remains quiet'],
    [qq{qr/(?x:# %{comment}\n)(?-x:# %{actual})/}, 1,
        'scoped /x disable restores real brace warning'],
    [qq{qr/((?-x)# %{actual}) # %{comment}\n/x}, 1,
        'persistent inline /x disable is restored after nested group'],
);

for my $case (@cases) {
    my ($regex, $error, $warnings) = compile_source($case->[0]);
    ok(defined($regex) && $error eq '', "$case->[2] compiles");
    is(scalar(@$warnings), $case->[1], "$case->[2] warning count");
    if ($case->[1]) {
        like($warnings->[0],
            qr/^Unescaped left brace in regex is passed through/,
            "$case->[2] retains real brace diagnostic");
    }
}

{
    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    my $pattern = '%{once}';
    my $regex = eval { qr/$pattern/ };
    ok(defined($regex), 'runtime pattern with real brace compiles');
    my $before = scalar @warnings;
    '' =~ $regex;
    '' =~ $regex;
    is($before, 1, 'construction emits one real brace warning');
    is(scalar(@warnings), 1,
        'matching twice does not repeat construction warning');
}

done_testing;
