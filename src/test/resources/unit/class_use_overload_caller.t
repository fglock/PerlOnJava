use Test::More tests => 3;

my ($warnings, $ok, $error) = ('', undef, '');
{
    local $SIG{__WARN__} = sub { $warnings .= shift };
    $ok = eval q{
        use v5.38;
        use experimental 'class';
        class ClassUseOverloadCaller {
            use overload '""' => method (@) { 'ok' }, fallback => 1;
        }
        1;
    };
    $error = $@;
}

is($error, '', 'class-local use overload compiles successfully');
is($warnings, '', 'class-local use overload does not redefine overloads in a module package');
is("ClassUseOverloadCaller"->new, 'ok', 'class-local overload is installed in the class package');
